# -*- coding: utf-8 -*-
"""Hash helpers — pure functions, no Firestore I/O."""
from firestore_repo import _hash_token, _hash_key, _user_id_from_sub


def test_hash_token_deterministic():
    assert _hash_token("abc") == _hash_token("abc")


def test_hash_token_distinguishes_inputs():
    assert _hash_token("abc") != _hash_token("abd")


def test_hash_token_hex_64():
    h = _hash_token("anything")
    assert len(h) == 64
    assert all(c in "0123456789abcdef" for c in h)


def test_hash_key_truncated_32():
    h = _hash_key("a longer body that would normally hash to 64 hex")
    assert len(h) == 32
    assert all(c in "0123456789abcdef" for c in h)


def test_user_id_from_sub_format():
    uid = _user_id_from_sub("google-sub-12345")
    assert uid.startswith("usr_")
    # base32 lowercase, no padding
    body = uid[len("usr_"):]
    assert len(body) == 24
    assert all(c in "abcdefghijklmnopqrstuvwxyz234567" for c in body)


def test_user_id_deterministic():
    assert _user_id_from_sub("sub-a") == _user_id_from_sub("sub-a")
    assert _user_id_from_sub("sub-a") != _user_id_from_sub("sub-b")
