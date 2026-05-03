from flask import Flask, g, jsonify, request
import os
from datetime import datetime, timedelta
from dotenv import load_dotenv
from email.utils import parseaddr
from zoneinfo import ZoneInfo

load_dotenv(dotenv_path=os.path.join(os.path.dirname(__file__), ".env"), override=True)

import firestore_repo

from parser import parse_schedule, build_iso_datetime
from calendar_service import create_event, delete_event, get_calendar_service
from googleapiclient.errors import HttpError
from gmail_service import get_unprocessed_emails, mark_processed
from google_auth import ReauthRequired
from oauth_routes import bp as oauth_bp
from user_context import ip_rate_limited, require_admin, require_auth, require_scheduler

KST = ZoneInfo("Asia/Seoul")

# Limits — guard against unbounded request bodies / Anthropic spend.
MAX_REQUEST_BYTES = 32 * 1024            # 32KB Flask-level cap
MAX_PARSE_MESSAGE_CHARS = 4_000          # per-message LLM input cap
MAX_ACCESS_REQUEST_NAME_CHARS = 100
MAX_EVENT_ID_CHARS = 1024

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = MAX_REQUEST_BYTES
app.register_blueprint(oauth_bp)


def _resolve_end_time(parsed: dict) -> str:
    end_time = parsed.get("end_time", "")
    if end_time:
        return end_time
    h, m = parsed["start_time"].split(":")
    return f"{str(int(h) + 1).zfill(2)}:{m}"


SOURCE_LABELS = {"sms": "SMS", "kakao": "KakaoTalk", "gmail": "Gmail", "rcs": "RCS", "naver": "네이버 메일"}


def _split_email_from(raw_from: str) -> tuple[str, str]:
    """Parse 'Name <email@domain>' into (name, domain)."""
    name, addr = parseaddr(raw_from or "")
    domain = addr.rsplit("@", 1)[-1] if "@" in addr else ""
    if not name:
        name = addr.split("@", 1)[0] if "@" in addr else addr
    return name, domain


def _build_title(base_title: str, sender: str, sender_org: str) -> str:
    if not sender:
        return base_title
    if sender_org:
        return f"{base_title}:{sender}({sender_org})"
    return f"{base_title}:{sender}"


def _build_description(parsed: dict, source: str, sender: str, sender_org: str) -> str:
    meta_parts = []
    location = parsed.get("location", "").strip()
    if location:
        meta_parts.append(location)
    meeting_url = parsed.get("meeting_url", "").strip()
    if meeting_url:
        meta_parts.append(meeting_url)
    meta_line = ", ".join(meta_parts)

    body = parsed.get("description", "").strip()
    if meta_line and body:
        return meta_line + "\n\n" + body
    return meta_line or body


def _save_event(user: dict, parsed: dict, source: str, sender: str = "", sender_org: str = "") -> dict:
    start_iso = build_iso_datetime(parsed["date"], parsed["start_time"])
    end_iso = build_iso_datetime(parsed["date"], _resolve_end_time(parsed))
    event = create_event(
        user,
        title=_build_title(parsed["title"], sender, sender_org),
        start_time=start_iso,
        end_time=end_iso,
        description=_build_description(parsed, source, sender, sender_org),
        location=parsed.get("location", ""),
        source=source,
    )
    # Track ownership so /event/<id> DELETE can verify the agent created it.
    if event.get("id"):
        try:
            firestore_repo.record_event_ownership(user["id"], event["id"], source)
        except Exception:
            # Ownership tracking is best-effort. A failure here just means the
            # user can't later delete this event through the API — they still can
            # delete it from Google Calendar directly.
            pass
    return event


def _process_message(
    user: dict,
    *,
    source: str,
    key: str,
    text: str,
    sender: str = "",
    sender_org: str = "",
) -> dict:
    """Dedup → parse → save → record. Returns:
      - {"skipped": True, "has_schedule": bool, "event": None, "parsed": None}
      - {"skipped": False, "has_schedule": bool, "event": dict|None, "parsed": dict}
    """
    existing = firestore_repo.find_dedup_event(user["id"], source, key)
    if existing:
        return {
            "skipped": True,
            "has_schedule": existing.get("has_schedule", False),
            "event": None,
            "parsed": None,
        }
    parsed = parse_schedule(text)
    event = None
    if parsed.get("has_schedule"):
        event = _save_event(user, parsed, source, sender=sender, sender_org=sender_org)
    firestore_repo.record_processed(
        user["id"], source, key, event["id"] if event else None
    )
    return {
        "skipped": False,
        "has_schedule": bool(parsed.get("has_schedule")),
        "event": event,
        "parsed": parsed,
    }


def _validate_message(data: dict) -> tuple[str | None, tuple | None]:
    """Returns (message, error_tuple). On success error_tuple is None."""
    if not data or "message" not in data:
        return None, (jsonify({"error": "message field required"}), 400)
    message = (data["message"] or "").strip()
    if not message:
        return None, (jsonify({"error": "message field required"}), 400)
    if len(message) > MAX_PARSE_MESSAGE_CHARS:
        return None, (jsonify({"error": "message too long"}), 413)
    return message, None


@app.errorhandler(ReauthRequired)
def _handle_reauth(_e):
    return jsonify({"error": "reauth_required"}), 401


@app.errorhandler(413)
def _handle_too_large(_e):
    return jsonify({"error": "request too large"}), 413


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok"})


@app.route("/me", methods=["GET"])
@require_auth
def me():
    u = g.user
    return jsonify({
        "email": u.get("email", ""),
        "name": u.get("name", ""),
        "picture": u.get("picture", ""),
    })


@app.route("/logout", methods=["POST"])
@require_auth
def logout():
    firestore_repo.clear_api_token(g.user["id"])
    return jsonify({"success": True})


@app.route("/parse", methods=["POST"])
@require_auth
def parse():
    message, err = _validate_message(request.get_json(silent=True) or {})
    if err:
        return err
    return jsonify(parse_schedule(message))


@app.route("/parse-and-save", methods=["POST"])
@require_auth
def parse_and_save():
    data = request.get_json(silent=True) or {}
    message, err = _validate_message(data)
    if err:
        return err

    source = (data.get("source") or "").strip()[:32]
    sender = (data.get("sender") or "").strip()[:200]
    sender_org = (data.get("sender_org") or "").strip()[:200]

    result = _process_message(
        g.user, source=source or "unknown", key=message, text=message,
        sender=sender, sender_org=sender_org,
    )

    if result["skipped"]:
        return jsonify({"success": False, "message": "Already processed (duplicate)", "skipped": True})
    if not result["has_schedule"]:
        return jsonify({"success": False, "message": "No schedule found", "parsed": result["parsed"]})

    return jsonify({
        "success": True,
        "message": "Schedule saved to Google Calendar",
        "parsed": result["parsed"],
        "event": result["event"],
    })


@app.route("/gmail/check", methods=["POST"])
@require_auth
def gmail_check():
    try:
        emails = get_unprocessed_emails(g.user, within_days=1)
        saved = []
        skipped = 0
        for email in emails:
            text = f"{email['subject']} {email['body']}"
            sender_name, sender_domain = _split_email_from(email.get("sender", ""))
            try:
                result = _process_message(
                    g.user, source="gmail", key=email["id"], text=text,
                    sender=sender_name, sender_org=sender_domain,
                )
                if result["skipped"]:
                    skipped += 1
                elif result["event"] is not None:
                    saved.append(result["event"])
            finally:
                # Label even on failure so we don't re-attempt forever; the dedup
                # collection is the source of truth for re-tries.
                try:
                    mark_processed(g.user, email["id"])
                except Exception:
                    pass
        return jsonify({"success": True, "checked": len(emails), "saved": len(saved), "skipped": skipped, "events": saved})
    except ReauthRequired:
        return jsonify({"success": False, "error": "reauth_required"}), 401
    except Exception:
        return jsonify({"success": False, "error": "internal_error"}), 500


@app.route("/gmail/check-all", methods=["POST"])
@require_scheduler
def gmail_check_all():
    summary = {"users": 0, "saved": 0, "skipped": 0, "errors": []}
    for user in firestore_repo.iter_active_users():
        summary["users"] += 1
        try:
            emails = get_unprocessed_emails(user, within_days=1)
            for email in emails:
                text = f"{email['subject']} {email['body']}"
                sender_name, sender_domain = _split_email_from(email.get("sender", ""))
                try:
                    result = _process_message(
                        user, source="gmail", key=email["id"], text=text,
                        sender=sender_name, sender_org=sender_domain,
                    )
                    if result["skipped"]:
                        summary["skipped"] += 1
                    elif result["event"] is not None:
                        summary["saved"] += 1
                finally:
                    try:
                        mark_processed(user, email["id"])
                    except Exception:
                        pass
        except ReauthRequired:
            summary["errors"].append({"user": user["id"], "err": "reauth_required"})
        except Exception as e:
            summary["errors"].append({"user": user["id"], "err": type(e).__name__})
    return jsonify(summary)


@app.route("/admin/users", methods=["GET"])
@require_admin
def admin_users():
    return jsonify({"users": firestore_repo.list_users_summary()})


@app.route("/access-request", methods=["POST"])
def access_request():
    """Public endpoint: friend who isn't in OAuth Test Users yet leaves an
    email so the developer can add them. Per-email throttle in repo layer +
    per-IP throttle here to bound enumeration via different addresses."""
    if ip_rate_limited():
        return jsonify({
            "success": False,
            "throttled": True,
            "message": "잠시 후 다시 시도해주세요.",
        }), 429

    data = request.get_json(silent=True) or {}
    email = (data.get("email") or "").strip().lower()
    name = (data.get("name") or "").strip()[:MAX_ACCESS_REQUEST_NAME_CHARS]

    # Basic email shape — refuses obvious garbage but isn't an RFC-strict check.
    if not email or "@" not in email or " " in email or len(email) > 254 or "." not in email.split("@", 1)[1]:
        return jsonify({"error": "valid email required"}), 400

    try:
        result = firestore_repo.add_pending_user(email=email, name=name)
    except Exception:
        return jsonify({"error": "internal_error"}), 500

    if result.get("throttled"):
        return jsonify({
            "success": True,
            "created": False,
            "throttled": True,
            "message": "잠시 후 다시 시도해주세요.",
        }), 429

    return jsonify({
        "success": True,
        "created": result["created"],
        "message": "요청이 접수되었습니다. 승인 후 다시 로그인해주세요." if result["created"] else "이미 접수된 요청입니다. 승인 대기 중.",
    })


@app.route("/admin/pending", methods=["GET"])
@require_admin
def admin_pending():
    return jsonify({"pending": firestore_repo.list_pending_users()})


@app.route("/admin/pending/<email>", methods=["POST"])
@require_admin
def admin_mark_added(email: str):
    ok = firestore_repo.mark_pending_added(email)
    return jsonify({"success": ok})


@app.route("/event/<event_id>", methods=["DELETE"])
@require_auth
def delete_event_route(event_id: str):
    if not event_id or len(event_id) > MAX_EVENT_ID_CHARS:
        return jsonify({"success": False, "error": "invalid event id"}), 400

    # Ownership check — only allow deleting events the agent created on this
    # user's behalf. Without this, a leaked bearer + iterated event IDs could
    # wipe arbitrary calendar entries.
    if not firestore_repo.is_event_owned(g.user["id"], event_id):
        return jsonify({"success": False, "error": "not_found_or_forbidden"}), 404

    try:
        delete_event(g.user, event_id)
        firestore_repo.forget_event_ownership(g.user["id"], event_id)
        return jsonify({"success": True})
    except HttpError as e:
        if e.resp.status in (404, 410):
            firestore_repo.forget_event_ownership(g.user["id"], event_id)
            return jsonify({"success": True, "note": "already deleted"})
        return jsonify({"success": False, "error": "calendar_api_error"}), 502
    except Exception:
        return jsonify({"success": False, "error": "internal_error"}), 500


@app.route("/stats", methods=["GET"])
@require_auth
def stats():
    now = datetime.now(KST)
    today_start = now.replace(hour=0, minute=0, second=0, microsecond=0)

    service = get_calendar_service(g.user)

    range_start = today_start - timedelta(days=30)
    range_end = today_start + timedelta(days=365)

    result = service.events().list(
        calendarId="primary",
        timeMin=range_start.isoformat(),
        timeMax=range_end.isoformat(),
        updatedMin=today_start.isoformat(),
        singleEvents=True,
        orderBy="updated",
    ).execute()
    events = result.get("items", [])

    today_added = []
    for e in events:
        created_str = e.get("created")
        if not created_str:
            continue
        created = datetime.fromisoformat(created_str.replace("Z", "+00:00"))
        if created >= today_start:
            today_added.append(e)

    today_added.sort(key=lambda e: e.get("created", ""), reverse=True)

    counts = {"sms": 0, "kakao": 0, "gmail": 0, "rcs": 0, "naver": 0, "unknown": 0}
    items = []
    for e in today_added:
        src = e.get("extendedProperties", {}).get("private", {}).get("source", "unknown")
        if src not in counts:
            src = "unknown"
        counts[src] += 1
        items.append({
            "id": e.get("id", ""),
            "title": e.get("summary", ""),
            "start": e["start"].get("dateTime", e["start"].get("date")),
            "source": src,
        })

    sms_combined = counts["sms"] + counts["rcs"]
    mail_combined = counts["gmail"] + counts["naver"]

    return jsonify({
        "today_added": {
            "total": len(today_added),
            "sms": sms_combined,
            "kakao": counts["kakao"],
            "gmail": mail_combined,
        },
        "today_list": items,
    })


if __name__ == "__main__":
    debug_mode = os.environ.get("FLASK_DEBUG", "").lower() in ("1", "true", "yes")
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 5000)), debug=debug_mode)
