# -*- coding: utf-8 -*-
"""Flask request authentication.

require_auth: validates `Authorization: Bearer <token>` against Firestore and
attaches the user document to flask.g.user.

require_scheduler: separate path for Cloud Scheduler that uses a shared secret
header (X-Scheduler-Secret) instead of per-user tokens.
"""
import hmac
import os
import time
from collections import deque
from functools import wraps
from threading import Lock

from flask import g, jsonify, request

import firestore_repo

# In-process LRU for token → user lookups. Every authenticated request would
# otherwise run a Firestore where-equality query just to resolve identity.
# 60s TTL is short enough that a token revocation (logout / disable_user) is
# noticed quickly. Cache is per-instance, so revocation across instances is
# eventually consistent.
_TOKEN_CACHE_TTL = 60.0
_TOKEN_CACHE_MAX = 256
_token_cache: dict[str, tuple[float, dict]] = {}


def _token_cache_get(token: str) -> dict | None:
    entry = _token_cache.get(token)
    if entry is None:
        return None
    expires_at, user = entry
    if time.monotonic() >= expires_at:
        _token_cache.pop(token, None)
        return None
    return user


def _token_cache_put(token: str, user: dict) -> None:
    if len(_token_cache) >= _TOKEN_CACHE_MAX:
        # Evict oldest entry. This is O(n) but only fires at the cap and the
        # cache is tiny.
        oldest = min(_token_cache.items(), key=lambda kv: kv[1][0])[0]
        _token_cache.pop(oldest, None)
    _token_cache[token] = (time.monotonic() + _TOKEN_CACHE_TTL, user)


def _token_cache_invalidate(token: str) -> None:
    _token_cache.pop(token, None)


# In-memory IP rate limiter for unauthenticated endpoints (e.g. /access-request).
# Each Cloud Run instance has its own state, so a multi-instance deployment
# multiplies the effective limit by max-instances. With max-instances=4 and
# 5 requests/5min per IP, the effective ceiling is ~20 requests/5min per IP —
# tolerable for a friend-only beta. For stronger guarantees use Cloud Armor.
_IP_LIMIT_REQS = 5
_IP_LIMIT_WINDOW_SEC = 300
_IP_TRACK_MAX = 10_000
_ip_requests: dict[str, deque[float]] = {}
_ip_lock = Lock()


def _client_ip() -> str:
    # Cloud Run / typical proxies forward the original client as the first
    # entry of X-Forwarded-For. Fall back to remote_addr (which under Cloud
    # Run is the front-end proxy, less useful but never empty).
    xff = request.headers.get("X-Forwarded-For", "")
    if xff:
        return xff.split(",", 1)[0].strip()
    return request.remote_addr or ""


def ip_rate_limited() -> bool:
    """Returns True if the current request's source IP has exceeded the
    rolling window limit and the caller should reject. Returns False (and
    records this hit) otherwise.

    Fail-open on missing IP and on dictionary-full conditions — the limiter
    is a best-effort throttle, not a hard quota."""
    ip = _client_ip()
    if not ip:
        return False
    now = time.monotonic()
    cutoff = now - _IP_LIMIT_WINDOW_SEC
    with _ip_lock:
        q = _ip_requests.get(ip)
        if q is None:
            if len(_ip_requests) >= _IP_TRACK_MAX:
                # Drop entries whose window has fully elapsed.
                stale = [k for k, dq in _ip_requests.items() if not dq or dq[-1] < cutoff]
                for k in stale:
                    _ip_requests.pop(k, None)
                if len(_ip_requests) >= _IP_TRACK_MAX:
                    return False  # tracking saturated, fail open
            q = deque()
            _ip_requests[ip] = q
        while q and q[0] < cutoff:
            q.popleft()
        if len(q) >= _IP_LIMIT_REQS:
            return True
        q.append(now)
        return False


def _extract_bearer_token() -> str | None:
    header = request.headers.get("Authorization", "")
    if header.startswith("Bearer "):
        return header[7:].strip() or None
    return None


def _check_header_secret(header_name: str, env_name: str) -> tuple[bool, tuple | None]:
    expected = os.environ.get(env_name)
    if not expected:
        return False, (jsonify({"error": "server misconfigured"}), 500)
    provided = request.headers.get(header_name, "")
    if not hmac.compare_digest(provided, expected):
        return False, (jsonify({"error": "unauthorized"}), 401)
    return True, None


def require_auth(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        token = _extract_bearer_token()
        if not token:
            return jsonify({"error": "unauthorized"}), 401
        user = _token_cache_get(token)
        if user is None:
            user = firestore_repo.get_user_by_api_token(token)
            if not user or user.get("disabled"):
                return jsonify({"error": "unauthorized"}), 401
            _token_cache_put(token, user)
        elif user.get("disabled"):
            _token_cache_invalidate(token)
            return jsonify({"error": "unauthorized"}), 401
        g.user = user
        return fn(*args, **kwargs)
    return wrapper


def require_scheduler(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        ok, err = _check_header_secret("X-Scheduler-Secret", "SCHEDULER_SECRET")
        if not ok:
            return err
        return fn(*args, **kwargs)
    return wrapper


def require_admin(fn):
    """Separate from scheduler: admin endpoints (`/admin/*`) use ADMIN_SECRET so
    rotation/leakage of the cron secret never grants PII access."""
    @wraps(fn)
    def wrapper(*args, **kwargs):
        ok, err = _check_header_secret("X-Admin-Secret", "ADMIN_SECRET")
        if not ok:
            return err
        return fn(*args, **kwargs)
    return wrapper
