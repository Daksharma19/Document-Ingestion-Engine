#!/usr/bin/env bash
# Stops whatever is listening on the project ports (8001 OCR, 8080 API, 5173 UI, 8501 analysis).
source "$(dirname "$0")/common.sh"
for p in 8001 8080 5173 8501; do
  pids=$( (command -v lsof >/dev/null && lsof -t -iTCP:$p -sTCP:LISTEN) || fuser $p/tcp 2>/dev/null || true)
  if [ -n "$pids" ]; then echo "Stopping :$p (pid $pids)"; kill $pids 2>/dev/null || true; fi
done
