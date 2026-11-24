#!/usr/bin/env bash
# Runs every test suite: Java (Maven), OCR service (unittest), frontend type-check.
source "$(dirname "$0")/common.sh"
rc=0
echo "== Java =="; ./mvnw -q test || rc=1
echo "== OCR service =="
if [ -x ocr-service/.venv/bin/python ]; then
  ocr-service/.venv/bin/python -m unittest discover -s ocr-service || rc=1
else echo "skipped (run scripts/ocr.sh once to create the venv)"; fi
echo "== Frontend =="
if [ -d frontend/node_modules ]; then (cd frontend && npx tsc --noEmit) || rc=1
else echo "skipped (run scripts/ui.sh once to install packages)"; fi
[ $rc -eq 0 ] && echo "ALL PASSED" || echo "FAILURES"
exit $rc
