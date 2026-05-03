"""Structured JSON logging for Cloud Run.

Cloud Logging extracts severity, message, and trace fields automatically when
each log line is a JSON document. The X-Cloud-Trace-Context header (set by
Cloud Run on every request) lets logs from the same request be grouped in the
console.

Usage:
    import logging
    log = logging.getLogger(__name__)
    log.info("processed", extra={"user_id": uid, "saved": n})
"""
import json
import logging
import os
import sys


class _JsonFormatter(logging.Formatter):
    _STD_ATTRS = frozenset(
        ("name", "msg", "args", "levelname", "levelno", "pathname", "filename",
         "module", "exc_info", "exc_text", "stack_info", "lineno", "funcName",
         "created", "msecs", "relativeCreated", "thread", "threadName",
         "processName", "process", "message", "taskName")
    )

    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "severity": record.levelname,
            "message": record.getMessage(),
            "logger": record.name,
        }
        # Pull through any `extra={...}` kwargs the caller passed.
        for key, value in record.__dict__.items():
            if key in self._STD_ATTRS or key.startswith("_"):
                continue
            payload[key] = value
        if record.exc_info:
            payload["exc_info"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False, default=str)


def configure() -> None:
    """Replaces the root handler with a JSON-emitting one. Idempotent."""
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(_JsonFormatter())
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(os.environ.get("LOG_LEVEL", "INFO").upper())
    # Tone down chatty third-party loggers — googleapiclient logs every
    # discovery fetch at INFO which would dominate the stream.
    logging.getLogger("googleapiclient.discovery").setLevel(logging.WARNING)
    logging.getLogger("urllib3.connectionpool").setLevel(logging.WARNING)
