# Shared helpers; sourced by the other scripts. Not run directly.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p logs
if [ -x "$ROOT/.tools/jdk-linux/bin/java" ]; then
  export JAVA_HOME="$ROOT/.tools/jdk-linux"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
need() { command -v "$1" >/dev/null || { echo "Missing dependency: $1 ($2)" >&2; exit 1; }; }
port_busy() { curl -s -m 2 -o /dev/null "http://127.0.0.1:$1" 2>/dev/null; }
refuse_if_busy() { # port name
  if port_busy "$1"; then echo "$2 already running on :$1 (use scripts/stop.sh first)"; exit 0; fi
}
