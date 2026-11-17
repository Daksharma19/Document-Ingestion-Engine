#!/usr/bin/env sh
# Creates the virtualenv on first run, then serves the OCR API on http://localhost:8001
set -e
here="$(cd "$(dirname "$0")" && pwd)"
if [ ! -x "$here/.venv/bin/python" ]; then
  python3 -m venv "$here/.venv"
  "$here/.venv/bin/python" -m pip install -r "$here/requirements.txt"
fi
exec "$here/.venv/bin/python" -m uvicorn app:app --app-dir "$here" --port 8001
