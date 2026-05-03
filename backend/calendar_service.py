# -*- coding: utf-8 -*-
import base64
import hashlib

from googleapiclient.errors import HttpError

from google_auth import get_service_for_user

# Calendar accepts event IDs in base32hex (RFC 4648 §7): lowercase a-v + 0-9,
# 5–1024 chars. We map standard base32 (Python's b32encode) onto the base32hex
# alphabet so a sha256 hash becomes a valid id.
_B32_STD = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
_B32HEX_LOWER = b"0123456789abcdefghijklmnopqrstuv"
_B32_TRANSLATION = bytes.maketrans(_B32_STD, _B32HEX_LOWER)


def get_calendar_service(user: dict):
    return get_service_for_user(user, "calendar", "v3")


def make_event_id(user_id: str, source: str, key: str) -> str:
    """Stable id derived from the message's source+key. Identical inputs
    always produce the same id, so duplicate inserts collide (HTTP 409) on
    the Calendar side, giving us native idempotency on top of the Firestore
    dedup record."""
    raw = f"v1|{user_id}|{source}|{key}".encode("utf-8")
    digest = hashlib.sha256(raw).digest()
    encoded = base64.b32encode(digest).translate(_B32_TRANSLATION).rstrip(b"=")
    return encoded.decode("ascii")


def create_event(
    user: dict,
    *,
    title: str,
    start_time: str,
    end_time: str,
    description: str = "",
    location: str = "",
    source: str = "",
    event_id: str | None = None,
) -> dict:
    """Inserts an event. When event_id is supplied and already exists on the
    user's calendar, returns the existing event (treated as an idempotent
    no-op rather than an error)."""
    service = get_calendar_service(user)
    body = {
        "summary": title,
        "location": location,
        "description": description,
        "start": {"dateTime": start_time, "timeZone": "Asia/Seoul"},
        "end": {"dateTime": end_time, "timeZone": "Asia/Seoul"},
        "extendedProperties": {
            "private": {"source": source}
        },
    }
    if event_id:
        body["id"] = event_id

    try:
        created = service.events().insert(calendarId="primary", body=body).execute()
    except HttpError as e:
        # 409 Conflict: another concurrent request (or a previous failed dedup
        # write) already created an event with this id. Fetch and return it.
        if event_id and e.resp.status == 409:
            created = service.events().get(calendarId="primary", eventId=event_id).execute()
        else:
            raise

    return {
        "id": created["id"],
        "title": created.get("summary", title),
        "start": created["start"].get("dateTime", start_time),
        "end": created["end"].get("dateTime", end_time),
        "link": created.get("htmlLink", ""),
    }


def delete_event(user: dict, event_id: str) -> None:
    service = get_calendar_service(user)
    service.events().delete(calendarId="primary", eventId=event_id).execute()
