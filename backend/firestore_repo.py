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


def _hash_token(raw_token: str) -> str:
    return hashlib.sha256(raw_token.encode("utf-8")).hexdigest()


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


def list_users_summary() -> list[dict]:
    out = []
    for snap in _get_client().collection(USERS_COLLECTION).stream():
        d = snap.to_dict() or {}
        out.append({
            "user_id": snap.id,
            "email": d.get("email", ""),
            "name": d.get("name", ""),
            "last_seen_at": d.get("last_seen_at").isoformat() if d.get("last_seen_at") else None,
            "disabled": d.get("disabled", False),
        })
    return out
