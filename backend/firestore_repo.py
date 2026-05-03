# -*- coding: utf-8 -*-
"""User repository backed by Firestore.

Documents are stored in the `users` collection, keyed by `usr_<base32(google_sub)>`.
The api_token is never stored in plain text — only its SHA-256 hash, which is
queried via a single-field equality lookup on every authenticated request.
"""
import base64
import hashlib
import os
import secrets
from datetime import datetime, timezone
from typing import Iterator

from google.cloud import firestore

USERS_COLLECTION = "users"
PENDING_USERS_COLLECTION = "pending_users"
USER_EVENTS_COLLECTION = "user_events"  # ownership records: doc id = "{user_id}_{event_id}"
DEDUP_COLLECTION = "processed_messages"  # idempotency: doc id = "{user_id}_{source}_{key_hash}"

PENDING_REQUEST_THROTTLE_SECONDS = 60


def _hash_token(raw_token: str) -> str:
    return hashlib.sha256(raw_token.encode("utf-8")).hexdigest()


def _hash_key(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:32]


def _user_id_from_sub(sub: str) -> str:
    digest = hashlib.sha256(sub.encode("utf-8")).digest()
    return "usr_" + base64.b32encode(digest)[:24].decode("ascii").lower().rstrip("=")


_client: firestore.Client | None = None


def _get_client() -> firestore.Client:
    global _client
    if _client is None:
        project = os.environ.get("GCP_PROJECT")
        _client = firestore.Client(project=project) if project else firestore.Client()
    return _client


def _doc_with_id(snapshot) -> dict | None:
    if not snapshot.exists:
        return None
    data = snapshot.to_dict() or {}
    data["id"] = snapshot.id
    return data


def get_user_by_api_token(raw_token: str) -> dict | None:
    if not raw_token:
        return None
    token_hash = _hash_token(raw_token)
    query = (
        _get_client()
        .collection(USERS_COLLECTION)
        .where(filter=firestore.FieldFilter("api_token_hash", "==", token_hash))
        .limit(1)
        .stream()
    )
    for snap in query:
        return _doc_with_id(snap)
    return None


def get_user_by_google_sub(sub: str) -> dict | None:
    user_id = _user_id_from_sub(sub)
    snap = _get_client().collection(USERS_COLLECTION).document(user_id).get()
    return _doc_with_id(snap)


def upsert_user_from_oauth(
    *,
    sub: str,
    email: str,
    name: str,
    picture: str,
    creds_dict: dict,
) -> tuple[dict, str]:
    """Create or update a user. Always rotates the api_token.

    Rotation policy: every successful OAuth callback issues a new token. Old
    Android installs become invalid — matches user intent ("I logged in again,
    my old install is gone") and prevents stale-token leakage.
    """
    user_id = _user_id_from_sub(sub)
    raw_token = secrets.token_urlsafe(32)
    now = datetime.now(timezone.utc)

    doc_ref = _get_client().collection(USERS_COLLECTION).document(user_id)
    existing = doc_ref.get()
    base = existing.to_dict() if existing.exists else {}

    payload = {
        "google_sub": sub,
        "email": email,
        "name": name,
        "picture": picture,
        "api_token_hash": _hash_token(raw_token),
        "api_token_prefix": raw_token[:8],
        "google_credentials": creds_dict,
        "last_seen_at": now,
        "disabled": False,
    }
    if not existing.exists:
        payload["created_at"] = now
        payload["gmail_label_id"] = None

    doc_ref.set({**base, **payload}, merge=True)
    snap = doc_ref.get()
    return _doc_with_id(snap), raw_token


def update_credentials(user_id: str, creds_dict: dict) -> None:
    _get_client().collection(USERS_COLLECTION).document(user_id).update({
        "google_credentials": creds_dict,
    })


def set_gmail_label_id(user_id: str, label_id: str) -> None:
    _get_client().collection(USERS_COLLECTION).document(user_id).update({
        "gmail_label_id": label_id,
    })


def set_gmail_last_history_id(user_id: str, history_id: str | int) -> None:
    """Tracks the high-water mark for Gmail incremental sync (history.list)."""
    _get_client().collection(USERS_COLLECTION).document(user_id).update({
        "gmail_last_history_id": str(history_id),
    })


def disable_user(user_id: str, *, reason: str = "") -> None:
    _get_client().collection(USERS_COLLECTION).document(user_id).update({
        "disabled": True,
        "disabled_reason": reason,
        "disabled_at": datetime.now(timezone.utc),
    })


def clear_api_token(user_id: str) -> None:
    _get_client().collection(USERS_COLLECTION).document(user_id).update({
        "api_token_hash": "",
        "api_token_prefix": "",
    })


def iter_active_users() -> Iterator[dict]:
    query = (
        _get_client()
        .collection(USERS_COLLECTION)
        .where(filter=firestore.FieldFilter("disabled", "==", False))
        .stream()
    )
    for snap in query:
        doc = _doc_with_id(snap)
        if doc:
            yield doc


def add_pending_user(email: str, name: str = "") -> dict:
    """Records an access request from someone not yet in OAuth Test Users.
    Returns {"created": bool, "throttled": bool, "doc": {...}}.

    Throttle: rejects re-requests from the same email within the last
    PENDING_REQUEST_THROTTLE_SECONDS to limit anonymous spam.
    """
    if not email:
        raise ValueError("email required")
    email_lc = email.strip().lower()
    doc_ref = _get_client().collection(PENDING_USERS_COLLECTION).document(email_lc)
    snap = doc_ref.get()
    now = datetime.now(timezone.utc)

    if snap.exists:
        existing = snap.to_dict() or {}
        last = existing.get("last_requested_at")
        if isinstance(last, datetime):
            elapsed = (now - last).total_seconds() if last.tzinfo else float("inf")
            if elapsed < PENDING_REQUEST_THROTTLE_SECONDS:
                return {"created": False, "throttled": True, "doc": {**existing, "id": email_lc}}
        doc_ref.update({
            "last_requested_at": now,
            "request_count": (existing.get("request_count", 0) + 1),
        })
        return {"created": False, "throttled": False, "doc": {**existing, "id": email_lc, "last_requested_at": now}}

    payload = {
        "email": email_lc,
        "name": name,
        "status": "pending",
        "first_requested_at": now,
        "last_requested_at": now,
        "request_count": 1,
    }
    doc_ref.set(payload)
    return {"created": True, "throttled": False, "doc": {**payload, "id": email_lc}}


def list_pending_users() -> list[dict]:
    out = []
    query = (
        _get_client()
        .collection(PENDING_USERS_COLLECTION)
        .where(filter=firestore.FieldFilter("status", "==", "pending"))
        .stream()
    )
    for snap in query:
        d = snap.to_dict() or {}
        out.append({
            "email": d.get("email", snap.id),
            "name": d.get("name", ""),
            "first_requested_at": d.get("first_requested_at").isoformat() if d.get("first_requested_at") else None,
            "request_count": d.get("request_count", 1),
        })
    return out


def mark_pending_added(email: str) -> bool:
    email_lc = (email or "").strip().lower()
    doc_ref = _get_client().collection(PENDING_USERS_COLLECTION).document(email_lc)
    if not doc_ref.get().exists:
        return False
    doc_ref.update({"status": "added", "added_at": datetime.now(timezone.utc)})
    return True


def list_users_summary(limit: int = 200) -> list[dict]:
    out = []
    query = _get_client().collection(USERS_COLLECTION).limit(limit).stream()
    for snap in query:
        d = snap.to_dict() or {}
        out.append({
            "user_id": snap.id,
            "email": d.get("email", ""),
            "name": d.get("name", ""),
            "last_seen_at": d.get("last_seen_at").isoformat() if d.get("last_seen_at") else None,
            "disabled": d.get("disabled", False),
        })
    return out


# --- Idempotency: skip re-processing the same message twice ---

def find_dedup_event(user_id: str, source: str, key: str) -> dict | None:
    """Returns the processed-messages doc if (user, source, key) was already
    processed (regardless of whether a schedule was found). Caller skips
    re-parsing on hit."""
    doc_id = f"{user_id}_{source}_{_hash_key(key)}"
    snap = _get_client().collection(DEDUP_COLLECTION).document(doc_id).get()
    return _doc_with_id(snap)


def record_processed(user_id: str, source: str, key: str, event_id: str | None) -> None:
    """Marks (user, source, key) as processed. event_id is the created Calendar
    event id (or None if has_schedule=False)."""
    doc_id = f"{user_id}_{source}_{_hash_key(key)}"
    _get_client().collection(DEDUP_COLLECTION).document(doc_id).set({
        "user_id": user_id,
        "source": source,
        "event_id": event_id or "",
        "has_schedule": event_id is not None,
        "processed_at": datetime.now(timezone.utc),
    })


# --- Ownership: only allow deletion of events the agent created ---

def record_event_ownership(user_id: str, event_id: str, source: str) -> None:
    doc_id = f"{user_id}_{event_id}"
    _get_client().collection(USER_EVENTS_COLLECTION).document(doc_id).set({
        "user_id": user_id,
        "event_id": event_id,
        "source": source,
        "created_at": datetime.now(timezone.utc),
    })


def is_event_owned(user_id: str, event_id: str) -> bool:
    doc_id = f"{user_id}_{event_id}"
    return _get_client().collection(USER_EVENTS_COLLECTION).document(doc_id).get().exists


def forget_event_ownership(user_id: str, event_id: str) -> None:
    doc_id = f"{user_id}_{event_id}"
    _get_client().collection(USER_EVENTS_COLLECTION).document(doc_id).delete()
