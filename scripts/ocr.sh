#!/usr/bin/env bash
# OCR service (Python, FastAPI) on :8001. First run creates the venv and installs requirements.
source "$(dirname "$0")/common.sh"
need python3 "Python 3.10+"
refuse_if_busy 8001 "OCR service"
exec "$ROOT/ocr-service/run.sh"
