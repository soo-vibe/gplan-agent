# -*- coding: utf-8 -*-
import json
import os
from datetime import datetime, timedelta
import anthropic


def parse_schedule(message: str) -> dict:
    today = datetime.now()
    tomorrow = today + timedelta(days=1)
    system_prompt = (
        "Extract schedule info from the message. "
        "Today is " + today.strftime("%Y-%m-%d") + " (" + today.strftime("%A") + "). "
        "Tomorrow is " + tomorrow.strftime("%Y-%m-%d") + ". "
        "When the message says 'tomorrow' or 'naeeil' or '내일', use " + tomorrow.strftime("%Y-%m-%d") + ". "
        "Return JSON only, no other text. "
        "Format: {\"has_schedule\": true, \"title\": \"...\", \"date\": \"YYYY-MM-DD\", "
        "\"start_time\": \"HH:MM\", \"end_time\": \"HH:MM\", \"location\": \"\", "
        "\"meeting_url\": \"\", \"description\": \"\"} "
        "If no schedule found, return {\"has_schedule\": false}. "
        "Rules: if no end_time, add 1 hour to start_time. if no time, use 09:00. "
        "meeting_url: extract Zoom/Google Meet/Microsoft Teams/Webex URL if present, else \"\". "
        "Message may be in Korean or English."
    )
    client = anthropic.Anthropic(api_key=os.environ["ANTHROPIC_API_KEY"])
    # Haiku로 다운그레이드 — 일정 추출은 단순 작업이라 Opus 대비 ~10x 비용 절감
    response = client.messages.create(
        model="claude-haiku-4-5",
        max_tokens=512,
        system=system_prompt,
        messages=[{"role": "user", "content": message}],
    )
    for block in response.content:
        if block.type == "text":
            try:
                return json.loads(block.text.strip())
            except json.JSONDecodeError:
                return {"has_schedule": False}
    return {"has_schedule": False}


def build_iso_datetime(date: str, time: str) -> str:
    return f"{date}T{time}:00+09:00"
