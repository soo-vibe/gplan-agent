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
# Anthropic recommends ≥1024-token blocks for caching. Marking ephemeral is harmless
# even if the block is short.
_STATIC_SYSTEM = (
    "Extract schedule info from the message. "
    "Return JSON only, no other text. "
    "Format: {\"has_schedule\": true, \"title\": \"...\", \"date\": \"YYYY-MM-DD\", "
    "\"start_time\": \"HH:MM\", \"end_time\": \"HH:MM\", \"location\": \"\", "
    "\"meeting_url\": \"\", \"description\": \"\"} "
    "If no schedule found, return {\"has_schedule\": false}. "
    "Rules: if no end_time, add 1 hour to start_time. if no time, use 09:00. "
    "meeting_url: extract Zoom/Google Meet/Microsoft Teams/Webex URL if present, else \"\". "
    "Message may be in Korean or English.\n\n"
    "Subject disambiguation (uses context fields supplied below: sender, user_name, source):\n"
    "1. First-person pronouns in the message body (\"저는\", \"제가\", \"전\", \"나는\", \"I\", \"my\") "
    "always refer to sender, NOT user_name. user_name is the calendar owner (the reader), "
    "not the speaker.\n"
    "2. A sender's personal circumstance or non-attendance excuse is NOT a schedule. "
    "If the message combines first-person + a reason phrase (\"있어서\", \"때문에\", \"관계로\") + "
    "a concession/adjustment phrase (\"늦게\", \"일찍\", \"합류\", \"못 갑니다\", \"대신\", \"양해\", \"불참\"), "
    "return {\"has_schedule\": false}. Example: \"저는 결혼식이 있어서 오후에 합류하겠습니다\" "
    "→ not a schedule (sender is explaining their own situation, not inviting user_name).\n"
    "3. Group invitations/announcements ARE schedules. If the message contains group-attendance "
    "signals (\"참석 부탁\", \"오세요\", \"안내드립니다\", \"공지\", \"다들\", \"여러분\") together with "
    "a time and/or location, treat it as a schedule user_name should attend.\n"
    "4. When source is \"kakao\", be conservative: only treat as a schedule when explicit "
    "group-invitation signals from rule 3 are present. Casual kakao chatter without those "
    "signals is not a schedule."
)

_client: anthropic.Anthropic | None = None

# Bound the Anthropic call so a slow upstream can't hang the entire Cloud
# Run worker thread. Cloud Run request timeout is 120s; keep margin for
# our own work after the LLM call (Calendar insert, Firestore writes).
_ANTHROPIC_TIMEOUT_SEC = 30.0


def _get_client() -> anthropic.Anthropic:
    global _client
    if _client is None:
        api_key = os.environ.get("ANTHROPIC_API_KEY")
        if not api_key:
            raise RuntimeError("ANTHROPIC_API_KEY unset")
        _client = anthropic.Anthropic(api_key=api_key, timeout=_ANTHROPIC_TIMEOUT_SEC)
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


# Errors the Anthropic SDK doesn't already auto-retry but we want to retry once.
_TRANSIENT_ERRORS = (
    anthropic.APITimeoutError,
    anthropic.APIConnectionError,
    anthropic.RateLimitError,
    anthropic.InternalServerError,
)


def parse_schedule(
    message: str,
    *,
    source: str = "",
    sender: str = "",
    sender_org: str = "",
    user_name: str = "",
) -> dict:
    today = datetime.now(KST)
    tomorrow = today + timedelta(days=1)
    context_parts: list[str] = []
    if sender:
        org_suffix = f" ({sender_org})" if sender_org else ""
        context_parts.append(f"Message sender: {sender}{org_suffix}.")
    if user_name:
        context_parts.append(
            f"Calendar owner / user_name (the reader, NOT the speaker): {user_name}."
        )
    if source:
        context_parts.append(f"Message source: {source}.")
    context_block = (" " + " ".join(context_parts)) if context_parts else ""
    dynamic_system = (
        "Today is " + today.strftime("%Y-%m-%d") + " (" + today.strftime("%A") + "). "
        "Tomorrow is " + tomorrow.strftime("%Y-%m-%d") + ". "
        "When the message says 'tomorrow' or 'naeeil' or '내일', use " + tomorrow.strftime("%Y-%m-%d") + "."
        + context_block
    )

    client = _get_client()
    # 256 covers our schema's typical output (~150 tokens). Retry once on a
    # JSON parse failure or a transient API error — Haiku 4.5 occasionally
    # truncates or wraps prose, and the SDK's built-in retries don't cover
    # client-side timeouts.
    for attempt in range(2):
        try:
            response = client.messages.create(
                model="claude-haiku-4-5",
                max_tokens=256,
                system=[
                    {"type": "text", "text": _STATIC_SYSTEM, "cache_control": {"type": "ephemeral"}},
                    {"type": "text", "text": dynamic_system},
                ],
                messages=[{"role": "user", "content": message}],
            )
        except _TRANSIENT_ERRORS:
            if attempt == 0:
                continue
            raise
        parsed = _parse_one_response(response)
        if parsed is not None:
            return parsed
    return {"has_schedule": False}


def build_iso_datetime(date: str, time: str) -> str:
    return f"{date}T{time}:00+09:00"
