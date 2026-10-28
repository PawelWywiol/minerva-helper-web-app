#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if command -v java >/dev/null; then
  exec ./gradlew "$@"
fi

exec docker run --rm \
  -u "$(id -u):$(id -g)" \
  -e GRADLE_USER_HOME=/app/.gradle/home \
  -v "$PWD:/app" -w /app \
  gradle:9.8.2-jdk25 ./gradlew "$@"
