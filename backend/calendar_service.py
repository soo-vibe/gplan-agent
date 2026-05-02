# -*- coding: utf-8 -*-
from google_auth import get_service_for_user


def get_calendar_service(user: dict):
    return get_service_for_user(user, "calendar", "v3")


def create_event(
    user: dict,
    *,
    title: str,
    start_time: str,
    end_time: str,
    description: str = "",
    location: str = "",
    source: str = "",
) -> dict:
    service = get_calendar_service(user)
    event = {
        "summary": title,
        "location": location,
        "description": description,
        "start": {"dateTime": start_time, "timeZone": "Asia/Seoul"},
        "end": {"dateTime": end_time, "timeZone": "Asia/Seoul"},
        "extendedProperties": {
            "private": {"source": source}
        },
    }
    created = service.events().insert(calendarId="primary", body=event).execute()
    return {
        "id": created["id"],
        "title": created["summary"],
        "start": created["start"]["dateTime"],
        "end": created["end"]["dateTime"],
        "link": created.get("htmlLink", ""),
    }
