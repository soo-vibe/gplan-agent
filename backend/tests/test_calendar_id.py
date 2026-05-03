"""make_event_id contract: deterministic, in base32hex (lowercase a-v + 0-9),
length within Calendar's 5–1024 char window."""

from calendar_service import make_event_id

ALLOWED = set("0123456789abcdefghijklmnopqrstuv")


def test_deterministic():
    a = make_event_id("usr_abc", "gmail", "msg-123")
    b = make_event_id("usr_abc", "gmail", "msg-123")
    assert a == b


def test_different_users_differ():
    a = make_event_id("usr_a", "gmail", "msg-123")
    b = make_event_id("usr_b", "gmail", "msg-123")
    assert a != b


def test_different_sources_differ():
    a = make_event_id("usr_a", "gmail", "x")
    b = make_event_id("usr_a", "sms", "x")
    assert a != b


def test_alphabet_is_base32hex_lowercase():
    eid = make_event_id("usr_xyz", "gmail", "anything")
    assert set(eid) <= ALLOWED, f"unexpected chars: {set(eid) - ALLOWED}"


def test_length_within_calendar_bounds():
    eid = make_event_id("usr_xyz", "gmail", "anything")
    assert 5 <= len(eid) <= 1024


def test_handles_unicode_key():
    # The hash input is utf-8 encoded; korean text must not blow up.
    eid = make_event_id("usr_xyz", "sms", "5월 5일 저녁식사 예약")
    assert set(eid) <= ALLOWED
