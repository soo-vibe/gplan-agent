# -*- coding: utf-8 -*-
import json
import os
import re
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import anthropic

# Haiku 4.5 frequently wraps JSON in ```json ... ``` markdown fences despite
# the prompt saying "Return JSON only, no other text". Strip them defensively.
_FENCE_RE = re.compile(r"^\s*```(?:json)?\s*\n?(.*?)\n?\s*```\s*$", re.DOTALL)

KST = ZoneInfo("Asia/Seoul")

# Static instructions — eligible for Anthropic prompt caching (90% input-token discount).
_STATIC_SYSTEM = (
    "Extract schedule info from the message. "
    "Return JSON only, no other text. "
    "Format: {\"has_schedule\": true, \"title\": \"...\", \"date\": \"YYYY-MM-DD\", "
    "\"start_time\": \"HH:MM\", \"end_time\": \"HH:MM\", \"location\": \"\", "
    "\"meeting_url\": \"\", \"description\": \"\"} "
    "If no schedule found, return {\"has_schedule\": false}. "
    "Rules: if no end_time, add 1 hour to start_time. if no time, use 09:00. "
    "meeting_url: extract Zoom/Google Meet/Microsoft Teams/Webex URL if present, else \"\". "
    "Message may be in Korean or English."
)

# Anthropic recommends ≥1024-token blocks for caching; pad with style notes if needed.
# Today's static prompt is ~600 chars and falls below the threshold, but caching will
# kick in once usage volume warrants. Marking ephemeral is harmless either way.

_client: anthropic.Anthropic | None = None


def _get_client() -> anthropic.Anthropic:
    global _client
    if _client is None:
        api_key = os.environ.get("ANTHROPIC_API_KEY")
        if not api_key:
            raise RuntimeError("ANTHROPIC_API_KEY unset")
        _client = anthropic.Anthropic(api_key=api_key)
    return _client


def _parse_one_response(response) -> dict | None:
    """Returns the parsed JSON dict, or None on parse failure (caller may retry)."""
    for block in response.content:
        if block.type != "text":
            continue
        text = block.text.strip()
        m = _FENCE_RE.match(text)
        if m:
            text = m.group(1).strip()
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            return None
    return None


def parse_schedule(message: str) -> dict:
    today = datetime.now(KST)
    tomorrow = today + timedelta(days=1)
    dynamic_system = (
        "Today is " + today.strftime("%Y-%m-%d") + " (" + today.strftime("%A") + "). "
        "Tomorrow is " + tomorrow.strftime("%Y-%m-%d") + ". "
        "When the message says 'tomorrow' or 'naeeil' or '내일', use " + tomorrow.strftime("%Y-%m-%d") + "."
    )

    client = _get_client()
    # 256 covers our schema's typical output (~150 tokens). Retry once on a
    # JSON parse failure — Haiku 4.5 has been seen to occasionally truncate or
    # emit prose around the JSON; a fresh sample usually fixes it.
    for _attempt in range(2):
        response = client.messages.create(
            model="claude-haiku-4-5",
            max_tokens=256,
            system=[
                {"type": "text", "text": _STATIC_SYSTEM, "cache_control": {"type": "ephemeral"}},
                {"type": "text", "text": dynamic_system},
            ],
            messages=[{"role": "user", "content": message}],
        )
        parsed = _parse_one_response(response)
        if parsed is not None:
            return parsed
    return {"has_schedule": False}


def build_iso_datetime(date: str, time: str) -> str:
    return f"{date}T{time}:00+09:00"
