#!/usr/bin/env bash
# Benchmark/analysis dashboard (Streamlit) on :8501.  Override with PORT=...
#   scripts/analysis.sh                -> dashboard
#   scripts/analysis.sh run [DIR...]   -> headless benchmark run (default: test-docs)
source "$(dirname "$0")/common.sh"
need python3 "Python 3.10+"
[ "${1:-}" = "run" ] || refuse_if_busy "${PORT:-8501}" "Analysis dashboard"
exec bash "$ROOT/bench/bench.sh" "$@"
