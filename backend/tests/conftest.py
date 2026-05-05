"""Shared pytest fixtures.

The parser imports the Anthropic client lazily, but we set a dummy key so
imports stay cheap and don't hit live env config.
"""
import os
import sys
from pathlib import Path

# Insert the backend dir on sys.path so `import parser` resolves to the
# project, not any site-packages collision.
_BACKEND = Path(__file__).resolve().parent.parent
if str(_BACKEND) not in sys.path:
    sys.path.insert(0, str(_BACKEND))

os.environ.setdefault("ANTHROPIC_API_KEY", "test-key-not-used")
