#!/bin/bash
# scripts/loadtest.sh
#
# Builds the server, starts it on :8080, and hammers it with wrk.
# Requires: Maven, wrk  (macOS: brew install maven wrk)
#
# Usage: bash scripts/loadtest.sh [connections] [duration]
#   defaults: 16 connections, 30s, 4 wrk threads
#
# Why 16 connections? The server uses a fixed pool of 16 worker threads, and each
# worker stays pinned to one keep-alive connection until it closes. With more than
# 16 connections, the extras get no service until a worker frees up (30s idle
# timeout), so wrk would only be measuring 16 of them anyway.

set -euo pipefail
cd "$(dirname "$0")/.."

CONNECTIONS="${1:-16}"
DURATION="${2:-30s}"
THREADS=4

mvn -q -DskipTests package
# Request logging goes to /dev/null so the terminal isn't the bottleneck
java -jar target/java-http-server-1.0-SNAPSHOT.jar > /dev/null 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null' EXIT

# Wait for the port to open
for _ in $(seq 1 50); do
  curl -s -o /dev/null http://localhost:8080/hello && break
  sleep 0.1
done

echo "== Dynamic route: GET /users/42  (-t$THREADS -c$CONNECTIONS -d$DURATION) =="
wrk -t"$THREADS" -c"$CONNECTIONS" -d"$DURATION" --latency http://localhost:8080/users/42

echo
echo "== Static file: GET /index.html  (-t$THREADS -c$CONNECTIONS -d$DURATION) =="
wrk -t"$THREADS" -c"$CONNECTIONS" -d"$DURATION" --latency http://localhost:8080/index.html
