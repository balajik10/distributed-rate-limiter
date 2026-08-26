#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
base_url="${BASE_URL:-http://localhost:8080}"
api_key="${API_KEY:-}"
project="${COMPOSE_PROJECT_NAME:-distributed-rate-limiter}"
export COMPOSE_PROJECT_NAME="$project"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/rate-limiter-failure-demo.XXXXXX")"
redis_stopped=0

restore_redis() {
  if [[ "$redis_stopped" == "1" ]]; then
    docker compose start redis >/dev/null 2>&1 || true
  fi
  rm -rf "$tmp_dir"
}
trap restore_redis EXIT

for tool in docker curl jq; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Required tool not found: $tool" >&2; exit 2; }
done

auth_headers=()
if [[ -n "$api_key" ]]; then auth_headers=(-H "X-API-Key: $api_key"); fi

curl --fail --silent --show-error "${auth_headers[@]}" "$base_url/api/v1/policies" >/dev/null
curl --fail --silent --show-error "$base_url/actuator/health/readiness" >/dev/null

docker compose stop redis >/dev/null
redis_stopped=1

readiness_down=0
for _ in {1..30}; do
  status="$(curl -sS -o "$tmp_dir/readiness.json" -w '%{http_code}' "$base_url/actuator/health/readiness" || true)"
  if [[ "$status" == "503" ]]; then readiness_down=1; break; fi
  sleep 1
done
[[ "$readiness_down" == "1" ]] || { echo "Readiness did not converge to HTTP 503" >&2; exit 1; }

unique="failure-$(date +%s)-$$"
fail_open_status="$(curl --silent --show-error -o "$tmp_dir/fail-open.json" -w '%{http_code}' \
  -X POST "$base_url/api/v1/rate-limits/check" -H 'Content-Type: application/json' "${auth_headers[@]}" \
  --data "{\"policyId\":\"api-standard\",\"key\":\"$unique-open\",\"permits\":1}")"
[[ "$fail_open_status" == "200" ]]
jq -e '.allowed == true and .source == "FAIL_OPEN" and .reason == "BACKEND_UNAVAILABLE_FAIL_OPEN" and .degraded == true and .approximate == true and .remaining == -1 and .resetAtEpochMs == null' "$tmp_dir/fail-open.json" >/dev/null

fail_closed_status="$(curl --silent --show-error -o "$tmp_dir/fail-closed.json" -w '%{http_code}' \
  -X POST "$base_url/api/v1/rate-limits/check" -H 'Content-Type: application/json' "${auth_headers[@]}" \
  --data "{\"policyId\":\"login-strict\",\"key\":\"$unique-closed\",\"permits\":1}")"
[[ "$fail_closed_status" == "503" ]]
jq -e '.allowed == false and .source == "FAIL_CLOSED" and .reason == "BACKEND_UNAVAILABLE_FAIL_CLOSED" and .degraded == true and .approximate == false and .remaining == -1 and .resetAtEpochMs == null' "$tmp_dir/fail-closed.json" >/dev/null

[[ "$(curl -sS -o /dev/null -w '%{http_code}' "$base_url/actuator/health/liveness")" == "200" ]]

docker compose start redis >/dev/null
redis_stopped=0
readiness_up=0
for _ in {1..60}; do
  if [[ "$(curl -sS -o /dev/null -w '%{http_code}' "$base_url/actuator/health/readiness" || true)" == "200" ]]; then readiness_up=1; break; fi
  sleep 1
done
[[ "$readiness_up" == "1" ]] || { echo "Readiness did not recover" >&2; exit 1; }

recovery_status="$(curl --silent --show-error -o "$tmp_dir/recovery.json" -w '%{http_code}' \
  -X POST "$base_url/api/v1/rate-limits/check" -H 'Content-Type: application/json' "${auth_headers[@]}" \
  --data "{\"policyId\":\"login-strict\",\"key\":\"$unique-recovered\",\"permits\":1}")"
[[ "$recovery_status" == "200" ]]
echo "Failure-mode demo passed: fail open=200 degraded, fail closed=503, liveness=200, readiness recovered"
