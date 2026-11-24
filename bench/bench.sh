#!/usr/bin/env bash
# One entry point for the parser benchmark.
#   bench/bench.sh            -> open the dashboard (run + analyse in the browser)
#   bench/bench.sh run DIR..  -> headless run, writes bench/results/<timestamp>/
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
PY="$HERE/.venv/bin/python"
if [ ! -x "$PY" ]; then
  python3 -m venv "$HERE/.venv"
  "$HERE/.venv/bin/pip" install -r "$HERE/requirements.txt"
fi
if [ "${1:-}" = "run" ]; then
  shift
  exec "$PY" "$HERE/run_bench.py" "${@:-test-docs}"
fi
exec "$PY" -m streamlit run "$HERE/dashboard.py" --server.port "${PORT:-8501}" --server.headless true --browser.gatherUsageStats false
