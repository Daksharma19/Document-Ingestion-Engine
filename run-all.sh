set -u
cd "$(dirname "$0")"
ROOT="$PWD"
mkdir -p logs
PIDS=()

up() { curl -s -m 2 "$1" 2>/dev/null | grep -q '"UP"'; }

wait_for() { 
  printf 'Waiting for %s' "$1"
  for _ in $(seq "$3"); do
    if up "$2"; then echo " ... UP"; return 0; fi
    printf '.'; sleep 1
  done
  echo " ... did not come up. Last lines of $4:"; tail -20 "$4"; return 1
}

cleanup() {
  if [ ${#PIDS[@]} -gt 0 ]; then
    echo; echo "Stopping services..."
    for p in "${PIDS[@]}"; do kill -- -"$p" 2>/dev/null || kill "$p" 2>/dev/null; done
    wait 2>/dev/null
  fi
  exit 0
}
trap cleanup INT TERM

if [ -x "$ROOT/.tools/jdk-linux/bin/java" ]; then
  export JAVA_HOME="$ROOT/.tools/jdk-linux"
elif ! command -v java >/dev/null; then
  echo "No Java found: put a JDK 21 in .tools/jdk-linux or install one." >&2; exit 1
fi

# --- 1. OCR service
if up http://127.0.0.1:8001/health; then
  echo "OCR service already running on :8001"
else
  setsid "$ROOT/ocr-service/run.sh" > logs/ocr.log 2>&1 < /dev/null &
  PIDS+=($!)
  wait_for "OCR service" http://127.0.0.1:8001/health 120 logs/ocr.log || echo "Continuing: the API falls back to Tesseract."
fi

# --- 2. API (clean compile first, so stale classes never cause 500s)
if up http://localhost:8080/health; then
  echo "API already running on :8080"
else
  setsid ./mvnw -q clean spring-boot:run > logs/api.log 2>&1 < /dev/null &
  PIDS+=($!)
  wait_for "API" http://localhost:8080/health 240 logs/api.log || cleanup
fi

# --- 3. Frontend
if curl -s -m 2 -o /dev/null http://localhost:5173; then
  echo "Frontend already running on :5173"
else
  [ -d frontend/node_modules ] || (echo "Installing frontend packages..."; npm --prefix frontend install)
  setsid npm --prefix frontend run dev > logs/frontend.log 2>&1 < /dev/null &
  PIDS+=($!)
  printf 'Waiting for frontend'
  for _ in $(seq 60); do curl -s -m 2 -o /dev/null http://localhost:5173 && break; printf '.'; sleep 1; done
  echo " ... UP"
fi

# --- 4. Analysis dashboard
if [ -z "${NO_ANALYSIS:-}" ]; then
  if curl -s -m 2 -o /dev/null http://localhost:8501; then
    echo "Analysis dashboard already running on :8501"
  else
    setsid bash "$ROOT/bench/bench.sh" > logs/analysis.log 2>&1 < /dev/null &
    PIDS+=($!)
    printf 'Waiting for analysis dashboard'
    for _ in $(seq 120); do curl -s -m 2 -o /dev/null http://localhost:8501 && break; printf '.'; sleep 1; done
    echo " ... UP"
  fi
fi

cat <<EOF

docextract is running:
  UI        http://localhost:5173
  API       http://localhost:8080   (POST /api/extract, POST /api/parse, GET /api/schemas)
  OCR       http://127.0.0.1:8001
  Analysis  http://localhost:8501
  Logs      $ROOT/logs/
EOF

if [ ${#PIDS[@]} -eq 0 ]; then echo "Nothing was started by this script."; exit 0; fi
echo "Press Ctrl+C to stop."
wait
