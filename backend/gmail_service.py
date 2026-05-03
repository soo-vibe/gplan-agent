# -*- coding: utf-8 -*-
import base64

from googleapiclient.errors import HttpError

import firestore_repo
from google_auth import get_service_for_user

PROCESSED_LABEL = "ScheduleAgent/Processed"


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


def get_unprocessed_emails(user: dict, within_days: int = 1) -> list:
    service = get_gmail_service(user)
    query = f"newer_than:{within_days}d -label:{PROCESSED_LABEL}"

    results = service.users().messages().list(
        userId="me", q=query, maxResults=20
    ).execute()
    messages = results.get("messages", [])

    emails = []
    for msg in messages:
        detail = service.users().messages().get(
            userId="me", id=msg["id"], format="full"
        ).execute()
        headers = {h["name"]: h["value"] for h in detail["payload"]["headers"]}
        emails.append({
            "id": msg["id"],
            "subject": headers.get("Subject", ""),
            "sender": headers.get("From", ""),
            "body": _extract_body(detail["payload"])[:1500],
        })
    return emails


def mark_processed(user: dict, message_id: str) -> None:
    service = get_gmail_service(user)
    label_id = _ensure_label_id(user, service)
    service.users().messages().modify(
        userId="me",
        id=message_id,
        body={"addLabelIds": [label_id]},
    ).execute()
