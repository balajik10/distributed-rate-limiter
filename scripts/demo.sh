#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
base_url="${BASE_URL:-http://localhost:8080}"
api_key="${API_KEY:-}"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/rate-limiter-demo.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

for tool in bash curl jq; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Required tool not found: $tool" >&2; exit 2; }
done

auth_headers=()
if [[ -n "$api_key" ]]; then
  auth_headers=(-H "X-API-Key: $api_key")
fi

ready=0
for _ in {1..60}; do
  if [[ "$(curl -sS -o /dev/null -w '%{http_code}' "$base_url/actuator/health/readiness" || true)" == "200" ]]; then
    ready=1
    break
  fi
  sleep 1
done
[[ "$ready" == "1" ]] || { echo "Service did not become ready within 60 seconds" >&2; exit 1; }

echo "Sanitized policies"
curl --fail --silent --show-error "${auth_headers[@]}" "$base_url/api/v1/policies" | tee "$tmp_dir/policies.json" | jq .
for policy in api-standard login-strict search-default; do
  jq -e --arg policy "$policy" '.. | objects | select(.id? == $policy or .policyId? == $policy)' "$tmp_dir/policies.json" >/dev/null
done

unique="demo-$(date +%s)-$$"
stable_fields='["allowed","policyId","policyVersion","algorithm","limit","remaining","grantedPermits","retryAfterMs","resetAtEpochMs","source","reason","approximate","degraded","requestId"]'

check_once() {
  local policy="$1" key="$2" body_file="$3" header_file="$4"
  local request_id="demo:${policy}:$$"
  local status
  status="$(curl --silent --show-error -D "$header_file" -o "$body_file" -w '%{http_code}' \
    -X POST "$base_url/api/v1/rate-limits/check" \
    -H 'Content-Type: application/json' \
    -H "X-Request-Id: $request_id" \
    "${auth_headers[@]}" \
    --data "{\"policyId\":\"$policy\",\"key\":\"$key\",\"permits\":1}")"
  [[ "$status" == "200" ]] || { jq . "$body_file" >&2 || true; echo "Expected 200 for $policy, got $status" >&2; exit 1; }
  jq -e --argjson fields "$stable_fields" --arg id "$request_id" \
    '($fields - (keys)) == [] and .requestId == $id and .allowed == true and .grantedPermits == 1' "$body_file" >/dev/null
  grep -qi '^X-Request-Id:' "$header_file"
  grep -qi '^X-RateLimit-Limit:' "$header_file"
  grep -qi '^X-RateLimit-Source:' "$header_file"
  grep -qi '^Cache-Control: no-store' "$header_file"
  echo "$policy: $(jq -c '{allowed,algorithm,remaining,source,approximate,degraded}' "$body_file")"
}

check_once api-standard "$unique-api" "$tmp_dir/api.json" "$tmp_dir/api.headers"
check_once search-default "$unique-search" "$tmp_dir/search.json" "$tmp_dir/search.headers"

successes=0
denials=0
for attempt in {1..6}; do
  body="$tmp_dir/login-$attempt.json"
  headers="$tmp_dir/login-$attempt.headers"
  status="$(curl --silent --show-error -D "$headers" -o "$body" -w '%{http_code}' \
    -X POST "$base_url/api/v1/rate-limits/check" \
    -H 'Content-Type: application/json' \
    "${auth_headers[@]}" \
    --data "{\"policyId\":\"login-strict\",\"key\":\"$unique-login\",\"permits\":1}")"
  expected_status="200"
  if ((attempt == 6)); then expected_status="429"; fi
  [[ "$status" == "$expected_status" ]] || {
    jq . "$body" >&2 || true
    echo "login-strict attempt $attempt: expected $expected_status, got $status" >&2
    exit 1
  }
  case "$status" in
    200) successes=$((successes + 1));;
    429)
      denials=$((denials + 1))
      jq -e '.allowed == false and .reason == "LIMIT_EXCEEDED" and .retryAfterMs >= 1' "$body" >/dev/null
      grep -qi '^Retry-After:' "$headers"
      grep -qi '^X-RateLimit-Remaining:' "$headers"
      ;;
    *) jq . "$body" >&2 || true; echo "Unexpected login-strict status: $status" >&2; exit 1;;
  esac
done
[[ "$successes" == "5" && "$denials" == "1" ]]
echo "login-strict: five HTTP 200 responses followed by one HTTP 429"

echo "Selected metrics"
curl --fail --silent --show-error "${auth_headers[@]}" "$base_url/actuator/prometheus" \
  | grep -E '^(ratelimiter_decisions|ratelimiter_redis_calls|ratelimiter_local_cache_hits)' \
  | head -20 || true

echo "Demo completed successfully from $repo_root"
