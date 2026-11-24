#!/usr/bin/env bash
# Spring API on :8080.  scripts/api.sh          -> mvnw spring-boot:run (compiles first)
#                       scripts/api.sh --jar    -> build once, run the packaged jar (faster restarts)
source "$(dirname "$0")/common.sh"
need java "put a JDK 21 in .tools/jdk-linux or install one"
refuse_if_busy 8080 "API"
if [ "${1:-}" = "--jar" ]; then
  JAR=target/docextract-0.0.1-SNAPSHOT.jar
  [ -f "$JAR" ] || ./mvnw -q package -DskipTests
  exec java -jar "$JAR"
fi
exec ./mvnw -q spring-boot:run
