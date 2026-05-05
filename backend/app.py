import logging
import os
import uuid

from dotenv import load_dotenv
from flask import Flask, g, jsonify, request

load_dotenv(dotenv_path=os.path.join(os.path.dirname(__file__), ".env"), override=True)

import logging_setup

logging_setup.configure()
log = logging.getLogger("gplan")

from parser import parse_schedule
from user_context import require_auth

# Limits — guard against unbounded request bodies / Anthropic spend.
MAX_REQUEST_BYTES = 32 * 1024            # 32KB Flask-level cap
MAX_PARSE_MESSAGE_CHARS = 4_000          # per-message LLM input cap

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = MAX_REQUEST_BYTES


@app.before_request
def _attach_request_id():
    # Cloud Run propagates the trace via X-Cloud-Trace-Context (format:
    # "TRACE_ID/SPAN_ID;o=1"). Reusing it lets Cloud Logging group our
    # application logs with the request log automatically.
    raw = request.headers.get("X-Cloud-Trace-Context", "")
    g.request_id = raw.split("/", 1)[0] if raw else uuid.uuid4().hex[:16]


@app.errorhandler(413)
def _handle_too_large(_e):
    return jsonify({"error": "request too large"}), 413


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok"}), 200


@app.route("/parse", methods=["POST"])
@require_auth
def parse():
    data = request.get_json(silent=True) or {}
    message = (data.get("message") or "").strip()
    if not message:
        return jsonify({"error": "message field required"}), 400
    if len(message) > MAX_PARSE_MESSAGE_CHARS:
        return jsonify({"error": "message too long"}), 413
    return jsonify(parse_schedule(message))


if __name__ == "__main__":
    debug_mode = os.environ.get("FLASK_DEBUG", "").lower() in ("1", "true", "yes")
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 5000)), debug=debug_mode)
