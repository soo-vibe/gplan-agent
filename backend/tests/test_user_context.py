"""Token cache (60s LRU) and IP rate limiter behavior — both pure-logic so
no Flask app context is needed for the data structures, but ip_rate_limited
needs a request context (we use Flask's test_request_context)."""

from flask import Flask

import user_context


def test_token_cache_hit_and_expiry(monkeypatch):
    user_context._token_cache.clear()
    fake_now = [1000.0]
    monkeypatch.setattr(user_context.time, "monotonic", lambda: fake_now[0])

    user_context._token_cache_put("tok1", {"id": "u1"})
    assert user_context._token_cache_get("tok1") == {"id": "u1"}

    fake_now[0] += user_context._TOKEN_CACHE_TTL + 1
    assert user_context._token_cache_get("tok1") is None


def test_token_cache_eviction_at_cap(monkeypatch):
    user_context._token_cache.clear()
    fake_now = [1000.0]
    monkeypatch.setattr(user_context.time, "monotonic", lambda: fake_now[0])
    monkeypatch.setattr(user_context, "_TOKEN_CACHE_MAX", 3)

    for i in range(5):
        fake_now[0] += 1
        user_context._token_cache_put(f"t{i}", {"id": f"u{i}"})

    # Oldest two evicted; cap enforced.
    assert len(user_context._token_cache) <= 3


def test_ip_rate_limiter_blocks_after_limit(monkeypatch):
    user_context._ip_requests.clear()
    fake_now = [1000.0]
    monkeypatch.setattr(user_context.time, "monotonic", lambda: fake_now[0])

    app = Flask(__name__)
    with app.test_request_context(headers={"X-Forwarded-For": "203.0.113.5"}):
        for _ in range(user_context._IP_LIMIT_REQS):
            assert user_context.ip_rate_limited() is False
        # The next call should be throttled.
        assert user_context.ip_rate_limited() is True


def test_ip_rate_limiter_window_resets(monkeypatch):
    user_context._ip_requests.clear()
    fake_now = [1000.0]
    monkeypatch.setattr(user_context.time, "monotonic", lambda: fake_now[0])

    app = Flask(__name__)
    with app.test_request_context(headers={"X-Forwarded-For": "203.0.113.5"}):
        for _ in range(user_context._IP_LIMIT_REQS):
            user_context.ip_rate_limited()
        assert user_context.ip_rate_limited() is True

        # Advance past the window — old hits drop, fresh quota.
        fake_now[0] += user_context._IP_LIMIT_WINDOW_SEC + 1
        assert user_context.ip_rate_limited() is False


def test_ip_rate_limiter_per_ip(monkeypatch):
    user_context._ip_requests.clear()
    fake_now = [1000.0]
    monkeypatch.setattr(user_context.time, "monotonic", lambda: fake_now[0])

    app = Flask(__name__)
    with app.test_request_context(headers={"X-Forwarded-For": "1.2.3.4"}):
        for _ in range(user_context._IP_LIMIT_REQS):
            user_context.ip_rate_limited()
        assert user_context.ip_rate_limited() is True

    # Different IP starts with a fresh quota.
    with app.test_request_context(headers={"X-Forwarded-For": "5.6.7.8"}):
        assert user_context.ip_rate_limited() is False
