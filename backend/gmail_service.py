"""Gmail polling — incremental only.

Policy: only mail that arrives AFTER a user's install is processed. Historical
inbox content is never scanned. This matches the SMS / notification / RCS
listeners (which by their nature only see new arrivals).

Implementation:
  - First poll for a user: capture the mailbox's current historyId and return
    no messages. Any mail already in the inbox stays untouched.
  - Subsequent polls: users.history.list?startHistoryId=<saved> returns only
    new INBOX messages since the saved id.
  - historyId expires after ~7 days of inactivity. On 400/404, we reset to the
    current historyId without backfilling — consistent with the "no historical
    scan" policy. The gap is accepted as the cost of that policy.

The app-layer dedup (processed_messages) is the authoritative re-processing
guard; gmail_last_history_id is the high-water mark for incremental fetch.
"""
import base64
from collections.abc import Iterable

from googleapiclient.errors import HttpError

import firestore_repo
from google_auth import get_service_for_user

PROCESSED_LABEL = "ScheduleAgent/Processed"
HISTORY_PAGE_SIZE = 100  # cap per page; we paginate to drain
GET_BODY_TRUNCATE = 1500


def get_gmail_service(user: dict):
    return get_service_for_user(user, "gmail", "v1")


def _find_label_id(service, name: str) -> str | None:
    existing = service.users().labels().list(userId="me").execute().get("labels", [])
    for label in existing:
        if label["name"] == name:
            return label["id"]
    return None


def _ensure_label_id(user: dict, service) -> str:
    cached = user.get("gmail_label_id")
    if cached:
        return cached

    found = _find_label_id(service, PROCESSED_LABEL)
    if found:
        firestore_repo.set_gmail_label_id(user["id"], found)
        user["gmail_label_id"] = found
        return found

    try:
        created = service.users().labels().create(
            userId="me",
            body={
                "name": PROCESSED_LABEL,
                "labelListVisibility": "labelShow",
                "messageListVisibility": "show",
            },
        ).execute()
        label_id = created["id"]
    except HttpError as e:
        # 409: another concurrent request created the label first.
        if e.resp.status != 409:
            raise
        label_id = _find_label_id(service, PROCESSED_LABEL)
        if not label_id:
            raise

    firestore_repo.set_gmail_label_id(user["id"], label_id)
    user["gmail_label_id"] = label_id
    return label_id


def _extract_body(payload) -> str:
    if "parts" in payload:
        for part in payload["parts"]:
            if part["mimeType"] == "text/plain":
                data = part["body"].get("data", "")
                if data:
                    return base64.urlsafe_b64decode(data).decode("utf-8", errors="ignore")
        for part in payload["parts"]:
            inner = _extract_body(part)
            if inner:
                return inner
    data = payload.get("body", {}).get("data", "")
    if data:
        return base64.urlsafe_b64decode(data).decode("utf-8", errors="ignore")
    return ""


def _build_email(detail: dict) -> dict:
    headers = {h["name"]: h["value"] for h in detail["payload"]["headers"]}
    return {
        "id": detail["id"],
        "subject": headers.get("Subject", ""),
        "sender": headers.get("From", ""),
        "body": _extract_body(detail["payload"])[:GET_BODY_TRUNCATE],
    }


def _fetch_messages_full(service, message_ids: Iterable[str]) -> list[dict]:
    out = []
    for mid in message_ids:
        try:
            detail = service.users().messages().get(
                userId="me", id=mid, format="full"
            ).execute()
        except HttpError as e:
            # Message deleted between history listing and our fetch — skip.
            if e.resp.status in (404, 410):
                continue
            raise
        out.append(_build_email(detail))
    return out


def _current_history_id(service) -> str:
    profile = service.users().getProfile(userId="me").execute()
    return str(profile.get("historyId", ""))


def _history_message_ids(service, start_history_id: str) -> tuple[list[str], str]:
    """Walks history.list pages to collect message-ids added to INBOX since
    start_history_id. Returns (msg_ids, latest_history_id).

    Raises HttpError 404/400 when the saved historyId is too old (>7 days);
    caller should fall back to full scan.
    """
    msg_ids: list[str] = []
    seen: set[str] = set()
    page_token = None
    latest = start_history_id

    while True:
        params = {
            "userId": "me",
            "startHistoryId": start_history_id,
            "historyTypes": "messageAdded",
            "labelId": "INBOX",
            "maxResults": HISTORY_PAGE_SIZE,
        }
        if page_token:
            params["pageToken"] = page_token

        result = service.users().history().list(**params).execute()

        for entry in result.get("history", []):
            for added in entry.get("messagesAdded", []):
                msg = added.get("message", {})
                mid = msg.get("id")
                if mid and mid not in seen:
                    seen.add(mid)
                    msg_ids.append(mid)

        # historyId in the response is the mailbox's current history id (newest
        # known to Gmail), not max id in this page. Saving this advances our
        # high-water mark even when no messages were added.
        latest = str(result.get("historyId", latest))

        page_token = result.get("nextPageToken")
        if not page_token:
            break

    return msg_ids, latest


def get_unprocessed_emails(user: dict, within_days: int = 1) -> list:
    """Returns email dicts {id, subject, sender, body} for messages added to
    the user's INBOX since the last successful poll. The first poll captures
    the mailbox's current historyId without returning anything — historical
    mail is intentionally not parsed.

    `within_days` is unused and kept only for backward-compatible callers.
    """
    del within_days  # incremental sync ignores time-window
    service = get_gmail_service(user)
    last_history_id = user.get("gmail_last_history_id")

    if not last_history_id:
        # First run: capture current historyId, do not backfill.
        new_history_id = _current_history_id(service)
        if new_history_id:
            firestore_repo.set_gmail_last_history_id(user["id"], new_history_id)
            user["gmail_last_history_id"] = new_history_id
        return []

    try:
        msg_ids, new_history_id = _history_message_ids(service, last_history_id)
    except HttpError as e:
        # historyId expired (404) or invalid (400 invalidArgument); reset to
        # current high-water mark without backfilling, per the no-historical
        # policy.
        if e.resp.status not in (400, 404):
            raise
        msg_ids = []
        new_history_id = _current_history_id(service)

    emails = _fetch_messages_full(service, msg_ids)

    if new_history_id and new_history_id != last_history_id:
        firestore_repo.set_gmail_last_history_id(user["id"], new_history_id)
        user["gmail_last_history_id"] = new_history_id

    return emails


def mark_processed(user: dict, message_id: str) -> None:
    service = get_gmail_service(user)
    label_id = _ensure_label_id(user, service)
    service.users().messages().modify(
        userId="me",
        id=message_id,
        body={"addLabelIds": [label_id]},
    ).execute()
