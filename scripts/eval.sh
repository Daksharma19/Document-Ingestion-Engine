#!/usr/bin/env bash
# Evaluation harness over the labelled corpus. Extra args are passed through, e.g.
#   scripts/eval.sh --strategy=native-text --out=eval-results
source "$(dirname "$0")/common.sh"
need java "JDK 21"
./mvnw -q package -DskipTests
exec java -jar target/docextract-0.0.1-SNAPSHOT.jar eval "$@"
