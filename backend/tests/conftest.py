# -*- coding: utf-8 -*-
"""Shared pytest fixtures.

The backend module imports a few clients at module scope (anthropic, firestore)
that need their env preconditions satisfied before import. We set dummy values
here so pure-logic tests can import without doing any network I/O.
"""
import os
import sys
from pathlib import Path

# Insert the backend dir on sys.path so `import app` resolves to the project,
# not any site-packages collision.
_BACKEND = Path(__file__).resolve().parent.parent
if str(_BACKEND) not in sys.path:
    sys.path.insert(0, str(_BACKEND))

os.environ.setdefault("ANTHROPIC_API_KEY", "test-key-not-used")
os.environ.setdefault("OAUTH_STATE_SECRET", "test-state-secret-not-used")
os.environ.setdefault("SCHEDULER_SECRET", "test-scheduler-secret")
os.environ.setdefault("ADMIN_SECRET", "test-admin-secret")
