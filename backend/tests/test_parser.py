# -*- coding: utf-8 -*-
"""Parser fence-stripping logic — the JSON path that prevents Haiku 4.5's
markdown wrapping from breaking schedule extraction."""
import json
from types import SimpleNamespace

import parser as parser_mod


def _fake_response(text: str):
    return SimpleNamespace(content=[SimpleNamespace(type="text", text=text)])


def test_parses_plain_json():
    payload = '{"has_schedule": true, "title": "x", "date": "2026-05-05"}'
    out = parser_mod._parse_one_response(_fake_response(payload))
    assert out == {"has_schedule": True, "title": "x", "date": "2026-05-05"}


def test_strips_json_fence_with_language_tag():
    payload = '```json\n{"has_schedule": true, "title": "y"}\n```'
    out = parser_mod._parse_one_response(_fake_response(payload))
    assert out == {"has_schedule": True, "title": "y"}


def test_strips_bare_fence():
    payload = '```\n{"has_schedule": false}\n```'
    out = parser_mod._parse_one_response(_fake_response(payload))
    assert out == {"has_schedule": False}


def test_invalid_json_returns_none():
    payload = "not json at all"
    out = parser_mod._parse_one_response(_fake_response(payload))
    assert out is None


def test_korean_content_preserved():
    payload = '{"title": "회의", "date": "2026-05-05"}'
    out = parser_mod._parse_one_response(_fake_response(payload))
    assert out["title"] == "회의"


def test_non_text_block_skipped():
    response = SimpleNamespace(content=[SimpleNamespace(type="tool_use")])
    assert parser_mod._parse_one_response(response) is None
