# -*- coding: utf-8 -*-
"""OAuth login and callback.

Flow:
  1. App opens Custom Tabs to GET /oauth/login?nonce=<client-nonce>.
  2. Backend signs a CSRF state (which embeds the client nonce) and 302-redirects
     to Google's consent screen.
  3. Google redirects back to GET /oauth/callback with code+state.
  4. Backend verifies state, exchanges code, decodes id_token for user identity,
     upserts the user (rotating the api_token), and 302-redirects to the
     deep link gplanagent://login?token=...&email=...&nonce=<echoed>
  5. The Android LoginActivity intercepts that deep link and verifies the nonce
     against the value it stored before launching Custom Tabs. A rogue app on
     the device cannot forge a valid deep link without knowing the nonce.
"""
import hmac
import json
import os
import urllib.parse

# Google may return additional default scopes (e.g. openid). Without this,
# oauthlib raises a warning that the requested vs returned scopes differ.
os.environ.setdefault("OAUTHLIB_RELAX_TOKEN_SCOPE", "1")

from flask import Blueprint, jsonify, redirect, request
from google.oauth2 import id_token as google_id_token
from google.auth.transport import requests as google_requests
from google_auth_oauthlib.flow import Flow
from itsdangerous import BadSignature, SignatureExpired, URLSafeTimedSerializer

import firestore_repo
from google_auth import SCOPES, credentials_to_dict

bp = Blueprint("oauth", __name__)

DEEP_LINK_BASE = "gplanagent://login"
STATE_MAX_AGE_SECONDS = 600
MAX_NONCE_LENGTH = 128

# Module-scoped caches — these were re-parsed on every login/callback.
_serializer_cache: URLSafeTimedSerializer | None = None
_client_config_cache: dict | None = None


def _serializer() -> URLSafeTimedSerializer:
    global _serializer_cache
    if _serializer_cache is None:
        secret = os.environ.get("OAUTH_STATE_SECRET")
        if not secret:
            raise RuntimeError("OAUTH_STATE_SECRET unset")
        _serializer_cache = URLSafeTimedSerializer(secret, salt="oauth-state-v1")
    return _serializer_cache


def _client_config() -> dict:
    global _client_config_cache
    if _client_config_cache is None:
        raw = os.environ.get("GOOGLE_OAUTH_CLIENT")
        if not raw:
            raise RuntimeError("GOOGLE_OAUTH_CLIENT unset")
        _client_config_cache = json.loads(raw)
    return _client_config_cache


def _redirect_uri() -> str:
    base = os.environ.get("PUBLIC_BASE_URL", "").rstrip("/")
    if not base:
        raise RuntimeError("PUBLIC_BASE_URL unset")
    return f"{base}/oauth/callback"


def _build_flow(state: str | None = None) -> Flow:
    return Flow.from_client_config(
        _client_config(),
        scopes=SCOPES,
        redirect_uri=_redirect_uri(),
        state=state,
    )


def _deep_link(**params) -> str:
    return f"{DEEP_LINK_BASE}?{urllib.parse.urlencode(params)}"


@bp.route("/oauth/login", methods=["GET"])
def oauth_login():
    nonce = (request.args.get("nonce") or "").strip()[:MAX_NONCE_LENGTH]
    state = _serializer().dumps({"v": 2, "nonce": nonce})
    flow = _build_flow(state=state)
    auth_url, _ = flow.authorization_url(
        access_type="offline",
        prompt="consent",
        include_granted_scopes="true",
    )
    return redirect(auth_url, code=302)


@bp.route("/oauth/callback", methods=["GET"])
def oauth_callback():
    code = request.args.get("code")
    state = request.args.get("state")
    error = request.args.get("error")

    if error:
        return redirect(_deep_link(error=error), code=302)
    if not code or not state:
        return redirect(_deep_link(error="missing_params"), code=302)

    try:
        state_data = _serializer().loads(state, max_age=STATE_MAX_AGE_SECONDS)
    except SignatureExpired:
        return redirect(_deep_link(error="state_expired"), code=302)
    except BadSignature:
        return redirect(_deep_link(error="bad_state"), code=302)

    nonce = ""
    if isinstance(state_data, dict):
        nonce = (state_data.get("nonce") or "")[:MAX_NONCE_LENGTH]

    flow = _build_flow(state=state)
    try:
        flow.fetch_token(code=code)
    except Exception as e:
        return redirect(_deep_link(error=f"token_exchange_failed:{type(e).__name__}"), code=302)

    creds = flow.credentials

    granted = set(creds.scopes or [])
    required = {
        "https://www.googleapis.com/auth/calendar",
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/gmail.modify",
    }
    if required - granted:
        return redirect(_deep_link(error="missing_scopes", nonce=nonce), code=302)

    try:
        client_id = _client_config()["web"]["client_id"]
        idinfo = google_id_token.verify_oauth2_token(
            creds.id_token, google_requests.Request(), audience=client_id, clock_skew_in_seconds=10
        )
    except Exception as e:
        return redirect(_deep_link(error=f"id_token_invalid:{type(e).__name__}", nonce=nonce), code=302)

    sub = idinfo.get("sub")
    email = idinfo.get("email", "")
    name = idinfo.get("name", "")
    picture = idinfo.get("picture", "")
    if not sub:
        return redirect(_deep_link(error="no_subject", nonce=nonce), code=302)

    _, raw_token = firestore_repo.upsert_user_from_oauth(
        sub=sub,
        email=email,
        name=name,
        picture=picture,
        creds_dict=credentials_to_dict(creds),
    )

    return redirect(_deep_link(token=raw_token, email=email, nonce=nonce), code=302)


@bp.route("/oauth/debug", methods=["GET"])
def oauth_debug():
    """Returns diagnostic info — gated behind DEBUG_TOKEN env to prevent
    public scope/redirect-URI enumeration."""
    expected = os.environ.get("DEBUG_TOKEN") or ""
    provided = request.args.get("token") or ""
    if not expected or not hmac.compare_digest(provided, expected):
        return jsonify({"error": "unauthorized"}), 401
    return jsonify({
        "redirect_uri": _redirect_uri(),
        "scopes": SCOPES,
    })
