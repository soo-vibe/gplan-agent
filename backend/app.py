from flask import Flask, g, jsonify, request
import os
from datetime import datetime, timedelta
from dotenv import load_dotenv

load_dotenv(dotenv_path=os.path.join(os.path.dirname(__file__), ".env"), override=True)

import firestore_repo
from email.utils import parseaddr

from parser import parse_schedule, build_iso_datetime
from calendar_service import create_event, delete_event, get_calendar_service
from googleapiclient.errors import HttpError
from gmail_service import get_unprocessed_emails, mark_processed
from google_auth import ReauthRequired
from oauth_routes import bp as oauth_bp
from user_context import require_auth, require_scheduler

app = Flask(__name__)
app.register_blueprint(oauth_bp)


def _resolve_end_time(parsed: dict) -> str:
    end_time = parsed.get("end_time", "")
    if end_time:
        return end_time
    h, m = parsed["start_time"].split(":")
    return f"{str(int(h) + 1).zfill(2)}:{m}"


SOURCE_LABELS = {"sms": "SMS", "kakao": "KakaoTalk", "gmail": "Gmail", "rcs": "RCS"}


def _split_email_from(raw_from: str) -> tuple[str, str]:
    """Parse 'Name <email@domain>' into (name, domain). Both fields are best-effort."""
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
    # 첫 줄: 장소, meeting_url 등 핵심 정보를 콤마로 묶음. 빈 항목은 자동 생략.
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
    return create_event(
        user,
        title=_build_title(parsed["title"], sender, sender_org),
        start_time=start_iso,
        end_time=end_iso,
        description=_build_description(parsed, source, sender, sender_org),
        location=parsed.get("location", ""),
        source=source,
    )


@app.errorhandler(ReauthRequired)
def _handle_reauth(e):
    return jsonify({"error": "reauth_required", "detail": str(e)}), 401


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
    data = request.get_json()
    if not data or "message" not in data:
        return jsonify({"error": "message field required"}), 400
    return jsonify(parse_schedule(data["message"].strip()))


@app.route("/parse-and-save", methods=["POST"])
@require_auth
def parse_and_save():
    data = request.get_json()
    if not data or "message" not in data:
        return jsonify({"error": "message field required"}), 400

    message = data["message"].strip()
    source = data.get("source", "")
    sender = data.get("sender", "").strip()
    sender_org = data.get("sender_org", "").strip()

    parsed = parse_schedule(message)
    if not parsed.get("has_schedule"):
        return jsonify({"success": False, "message": "No schedule found", "parsed": parsed})

    event = _save_event(g.user, parsed, source, sender=sender, sender_org=sender_org)
    return jsonify({
        "success": True,
        "message": "Schedule saved to Google Calendar",
        "parsed": parsed,
        "event": event,
    })


@app.route("/gmail/check", methods=["POST"])
@require_auth
def gmail_check():
    try:
        emails = get_unprocessed_emails(g.user, within_days=1)
        saved = []
        for email in emails:
            text = f"{email['subject']} {email['body']}"
            sender_name, sender_domain = _split_email_from(email.get("sender", ""))
            parsed = parse_schedule(text)
            try:
                if parsed.get("has_schedule"):
                    saved.append(_save_event(g.user, parsed, "gmail", sender=sender_name, sender_org=sender_domain))
            finally:
                mark_processed(g.user, email["id"])
        return jsonify({"success": True, "checked": len(emails), "saved": len(saved), "events": saved})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/gmail/check-all", methods=["POST"])
@require_scheduler
def gmail_check_all():
    summary = {"users": 0, "saved": 0, "errors": []}
    for user in firestore_repo.iter_active_users():
        summary["users"] += 1
        try:
            emails = get_unprocessed_emails(user, within_days=1)
            for email in emails:
                text = f"{email['subject']} {email['body']}"
                sender_name, sender_domain = _split_email_from(email.get("sender", ""))
                parsed = parse_schedule(text)
                try:
                    if parsed.get("has_schedule"):
                        _save_event(user, parsed, "gmail", sender=sender_name, sender_org=sender_domain)
                        summary["saved"] += 1
                finally:
                    mark_processed(user, email["id"])
        except ReauthRequired:
            summary["errors"].append({"user": user["id"], "err": "reauth_required"})
        except Exception as e:
            summary["errors"].append({"user": user["id"], "err": f"{type(e).__name__}: {e}"})
    return jsonify(summary)


@app.route("/admin/users", methods=["GET"])
@require_scheduler
def admin_users():
    return jsonify({"users": firestore_repo.list_users_summary()})


@app.route("/event/<event_id>", methods=["DELETE"])
@require_auth
def delete_event_route(event_id: str):
    try:
        delete_event(g.user, event_id)
        return jsonify({"success": True})
    except HttpError as e:
        if e.resp.status in (404, 410):
            return jsonify({"success": True, "note": "already deleted"})
        return jsonify({"success": False, "error": str(e)}), e.resp.status
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/stats", methods=["GET"])
@require_auth
def stats():
    import pytz
    kst = pytz.timezone("Asia/Seoul")
    now = datetime.now(kst)

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

    counts = {"sms": 0, "kakao": 0, "gmail": 0, "rcs": 0, "unknown": 0}
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

    # SMS 카운터에 RCS도 합산해서 표시 (사용자 입장에선 둘 다 '문자')
    sms_combined = counts["sms"] + counts["rcs"]

    return jsonify({
        "today_added": {
            "total": len(today_added),
            "sms": sms_combined,
            "kakao": counts["kakao"],
            "gmail": counts["gmail"],
        },
        "today_list": items,
    })


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 5000)), debug=True)
