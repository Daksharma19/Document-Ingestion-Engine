#!/usr/bin/env bash
# Shows which services are up.
source "$(dirname "$0")/common.sh"
chk() { if curl -s -m 2 -o /dev/null "$2"; then printf '  %-10s UP    %s\n' "$1" "$2"; else printf '  %-10s down  %s\n' "$1" "$2"; fi; }
chk OCR http://127.0.0.1:8001/health
chk API http://localhost:8080/health
chk UI http://localhost:5173
chk Analysis http://localhost:8501
