#!/usr/bin/env bash
# Wait until each given service port reports {"status":"UP"} on /actuator/health.
#   scripts/wait-healthy.sh [PORT...]   (default: 8081 8082)
# A running container is not a ready service: Spring needs several seconds to start.
set -euo pipefail

ports=("$@")
[ ${#ports[@]} -gt 0 ] || ports=(8081 8082)
for port in "${ports[@]}"; do
  for _ in $(seq 1 60); do
    if curl -fsS "http://localhost:${port}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      echo "service on ${port} is UP"; continue 2
    fi
    sleep 2
  done
  echo "service on ${port} never became healthy"; exit 1
done
