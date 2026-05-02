# -*- coding: utf-8 -*-
"""Per-user Google credential management.

Credentials live inside each user's Firestore document. On each API call we
materialize a Credentials object, refresh it if expired, and write the new
access token back so concurrent requests don't all refresh independently.
"""
from datetime import datetime, timezone

from google.auth.exceptions import RefreshError
from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build

import firestore_repo

SCOPES = [
    "https://www.googleapis.com/auth/calendar",
    "https://www.googleapis.com/auth/gmail.readonly",
    "https://www.googleapis.com/auth/gmail.modify",
    "openid",
    "https://www.googleapis.com/auth/userinfo.email",
    "https://www.googleapis.com/auth/userinfo.profile",
]


class ReauthRequired(Exception):
    """Raised when a user's refresh token is no longer valid."""


def credentials_to_dict(creds: Credentials) -> dict:
    return {
        "token": creds.token,
        "refresh_token": creds.refresh_token,
        "token_uri": creds.token_uri,
        "client_id": creds.client_id,
        "client_secret": creds.client_secret,
        "scopes": list(creds.scopes or []),
        "expiry": creds.expiry.replace(tzinfo=timezone.utc).isoformat() if creds.expiry else None,
    }


def _credentials_from_dict(data: dict) -> Credentials:
    expiry = data.get("expiry")
    creds = Credentials(
        token=data.get("token"),
        refresh_token=data.get("refresh_token"),
        token_uri=data.get("token_uri"),
        client_id=data.get("client_id"),
        client_secret=data.get("client_secret"),
        scopes=data.get("scopes") or SCOPES,
    )
    if isinstance(expiry, str):
        try:
            creds.expiry = datetime.fromisoformat(expiry).replace(tzinfo=None)
        except ValueError:
            pass
    elif isinstance(expiry, datetime):
        creds.expiry = expiry.replace(tzinfo=None)
    return creds


def load_credentials_for_user(user: dict) -> Credentials:
    creds_dict = user.get("google_credentials") or {}
    creds = _credentials_from_dict(creds_dict)
    if creds.expired and creds.refresh_token:
        try:
            creds.refresh(Request())
        except RefreshError as e:
            firestore_repo.disable_user(user["id"], reason=f"refresh_failed: {e}")
            raise ReauthRequired(str(e)) from e
        firestore_repo.update_credentials(user["id"], credentials_to_dict(creds))
    return creds


def get_service_for_user(user: dict, api_name: str, version: str):
    return build(api_name, version, credentials=load_credentials_for_user(user), cache_discovery=False)
