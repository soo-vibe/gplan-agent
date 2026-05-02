# -*- coding: utf-8 -*-
"""Flask request authentication.

require_auth: validates `Authorization: Bearer <token>` against Firestore and
attaches the user document to flask.g.user.

require_scheduler: separate path for Cloud Scheduler that uses a shared secret
header (X-Scheduler-Secret) instead of per-user tokens.
"""
import os
from functools import wraps

from flask import g, jsonify, request

import firestore_repo


def _extract_bearer_token() -> str | None:
    header = request.headers.get("Authorization", "")
    if header.startswith("Bearer "):
        return header[7:].strip() or None
    return None


def require_auth(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        token = _extract_bearer_token()
        if not token:
            return jsonify({"error": "unauthorized"}), 401
        user = firestore_repo.get_user_by_api_token(token)
        if not user or user.get("disabled"):
            return jsonify({"error": "unauthorized"}), 401
        g.user = user
        return fn(*args, **kwargs)
    return wrapper


def require_scheduler(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        expected = os.environ.get("SCHEDULER_SECRET")
        if not expected:
            return jsonify({"error": "server misconfigured: SCHEDULER_SECRET unset"}), 500
        if request.headers.get("X-Scheduler-Secret") != expected:
            return jsonify({"error": "unauthorized"}), 401
        return fn(*args, **kwargs)
    return wrapper
