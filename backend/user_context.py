"""ID token-based authentication.

The Android app obtains a Google ID token via Google Sign-In and sends it
as `Authorization: Bearer <id_token>`. We verify locally:
  - signature against Google's public keys (cached by google-auth)
  - audience matches our Web OAuth client ID (GOOGLE_WEB_CLIENT_ID)
  - exp / iat windows with small clock-skew tolerance

A 60s TTL cache by token string skips re-verification of the same token
inside a short burst. The verification itself is cheap, but skipping
shaves off a few ms on tight loops and avoids Google JWKS lookups.
"""
import os
import time
from functools import wraps

from flask import jsonify, request
from google.auth.transport import requests as google_requests
from google.oauth2 import id_token as google_id_token

_TOKEN_CACHE_TTL = 60.0
_TOKEN_CACHE_MAX = 256
_token_cache: dict[str, float] = {}

_client_id_cache: str | None = None


def _client_id() -> str:
    global _client_id_cache
    if _client_id_cache is None:
        cid = os.environ.get("GOOGLE_WEB_CLIENT_ID", "").strip()
        if not cid:
            raise RuntimeError("GOOGLE_WEB_CLIENT_ID unset")
        _client_id_cache = cid
    return _client_id_cache


def _extract_bearer() -> str | None:
    header = request.headers.get("Authorization", "")
    if header.startswith("Bearer "):
        return header[7:].strip() or None
    return None


def _evict_if_full() -> None:
    if len(_token_cache) < _TOKEN_CACHE_MAX:
        return
    now = time.monotonic()
    stale = [k for k, exp in _token_cache.items() if exp <= now]
    for k in stale:
        _token_cache.pop(k, None)
    if len(_token_cache) >= _TOKEN_CACHE_MAX:
        oldest = min(_token_cache.items(), key=lambda kv: kv[1])[0]
        _token_cache.pop(oldest, None)


def require_auth(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        token = _extract_bearer()
        if not token:
            return jsonify({"error": "unauthorized"}), 401
        cached_until = _token_cache.get(token)
        if cached_until is not None and cached_until > time.monotonic():
            return fn(*args, **kwargs)
        try:
            google_id_token.verify_oauth2_token(
                token,
                google_requests.Request(),
                _client_id(),
                clock_skew_in_seconds=10,
            )
        except ValueError:
            return jsonify({"error": "unauthorized"}), 401
        _evict_if_full()
        _token_cache[token] = time.monotonic() + _TOKEN_CACHE_TTL
        return fn(*args, **kwargs)

    return wrapper
