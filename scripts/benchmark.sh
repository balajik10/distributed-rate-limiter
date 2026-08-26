#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

scenario="${SCENARIO:-strict-hot-key}"
rps="${RPS:-100}"
vus="${VUS:-20}"
duration="${DURATION:-15s}"
policy_id="${POLICY_ID:-benchmark-token-strict}"
key_cardinality="${KEY_CARDINALITY:-1}"
base_url="${BASE_URL:-}"
api_key="${API_KEY:-}"
metrics_url="${METRICS_URL:-}"
policy_url_base="${POLICY_URL_BASE:-}"
smoke=0

while (($#)); do
  case "$1" in
    --smoke) smoke=1; scenario=high-cardinality; rps=10; vus=2; duration=5s; policy_id=api-standard; key_cardinality=10; shift;;
    --scenario) scenario="${2:?missing scenario}"; shift 2;;
    --rps) rps="${2:?missing RPS}"; shift 2;;
    --vus) vus="${2:?missing VUS}"; shift 2;;
    --duration) duration="${2:?missing duration}"; shift 2;;
    --policy) policy_id="${2:?missing policy}"; shift 2;;
    --key-cardinality) key_cardinality="${2:?missing cardinality}"; shift 2;;
    --base-url) base_url="${2:?missing URL}"; shift 2;;
    *) echo "Unknown argument: $1" >&2; exit 2;;
  esac
done

case "$scenario" in strict-hot-key|leased-hot-key|mixed-80-20|algorithms|high-cardinality) ;; *) echo "Unsupported scenario: $scenario" >&2; exit 2;; esac
if [[ "$smoke" == "0" ]]; then
  case "$scenario:$policy_id" in
    strict-hot-key:*-strict|leased-hot-key:*-leased|mixed-80-20:*|high-cardinality:*|algorithms:benchmark-token-strict) ;;
    *) echo "Scenario and trusted benchmark policy do not match" >&2; exit 2;;
  esac
fi
if ! [[ "$rps" =~ ^[1-9][0-9]{0,5}$ ]] || ((rps > 100000)); then
  echo "RPS must be 1..100000" >&2
  exit 2
fi
if ! [[ "$vus" =~ ^[1-9][0-9]{0,4}$ ]] || ((vus > 10000)); then
  echo "VUS must be 1..10000" >&2
  exit 2
fi
if ! [[ "$key_cardinality" =~ ^[1-9][0-9]{0,6}$ ]] || ((key_cardinality > 1000000)); then
  echo "KEY_CARDINALITY must be 1..1000000" >&2
  exit 2
fi
if [[ "$scenario" == "mixed-80-20" ]] && ((key_cardinality < 2)); then
  echo "mixed-80-20 requires KEY_CARDINALITY >= 2 (one hot key plus cold keys)" >&2
  exit 2
fi
[[ "$duration" =~ ^([1-9][0-9]{0,3})(s|m)$ ]] || { echo "DURATION must be 1s..9999s or minutes" >&2; exit 2; }
[[ "$policy_id" =~ ^[a-z0-9][a-z0-9._-]{0,63}$ ]] || { echo "Invalid policy ID" >&2; exit 2; }
for tool in bash docker curl jq awk git; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Required tool not found: $tool" >&2; exit 2; }
done

curl_api() {
  if [[ -n "$api_key" ]]; then
    curl -H "X-API-Key: $api_key" "$@"
  else
    curl "$@"
  fi
}

docker_network_args=()
if [[ -z "$base_url" ]]; then
  app_container="$(docker compose ps -q app)"
  [[ -n "$app_container" ]] || { echo "The Compose app service must be running" >&2; exit 2; }
  compose_network="$(docker inspect --format '{{range $name, $config := .NetworkSettings.Networks}}{{$name}}{{println}}{{end}}' "$app_container" | head -n 1)"
  [[ -n "$compose_network" ]] || { echo "Could not resolve the app's Compose network" >&2; exit 2; }
  docker_network_args=(--network "$compose_network")
  base_url="http://app:8080"
  metrics_url="${metrics_url:-http://localhost:8080/actuator/prometheus}"
  policy_url_base="${policy_url_base:-http://localhost:8080/api/v1/policies}"
else
  metrics_url="${metrics_url:-$base_url/actuator/prometheus}"
  policy_url_base="${policy_url_base:-$base_url/api/v1/policies}"
fi
[[ "$base_url" =~ ^https?://[A-Za-z0-9._:-]+$ ]] || { echo "BASE_URL contains unsupported characters" >&2; exit 2; }
[[ "$metrics_url" =~ ^https?://[A-Za-z0-9._:/-]+$ ]] || { echo "METRICS_URL contains unsupported characters" >&2; exit 2; }
[[ "$policy_url_base" =~ ^https?://[A-Za-z0-9._:/-]+$ ]] || { echo "POLICY_URL_BASE contains unsupported characters" >&2; exit 2; }

mkdir -p build/results
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
raw_path="build/results/k6-${scenario}-${timestamp}.json"
summary_path="build/results/k6-${scenario}-${timestamp}-summary.json"
summary_markdown_path="build/results/k6-${scenario}-${timestamp}-summary.md"
metadata_path="build/results/k6-${scenario}-${timestamp}-environment.txt"
metrics_before_path="build/results/k6-${scenario}-${timestamp}-metrics-before.prom"
metrics_after_path="build/results/k6-${scenario}-${timestamp}-metrics-after.prom"

if [[ "$scenario" == "algorithms" ]]; then
  configured_batch=1
else
  policy_json="$(curl_api --fail --silent --show-error "$policy_url_base/$policy_id")"
  configured_batch="$(jq -er '.localCache.maxLeaseSize' <<<"$policy_json")"
fi
curl_api --fail --silent --show-error "$metrics_url" >"$metrics_before_path" 2>/dev/null || : >"$metrics_before_path"

docker run --rm --user "$(id -u):$(id -g)" ${docker_network_args[@]+"${docker_network_args[@]}"} \
  -v "$repo_root/benchmarks/k6:/scripts:ro" \
  -v "$repo_root/build/results:/results" \
  -e BASE_URL="$base_url" \
  -e POLICY_ID="$policy_id" \
  -e KEY_CARDINALITY="$key_cardinality" \
  -e RPS="$rps" \
  -e VUS="$vus" \
  -e DURATION="$duration" \
  -e SCENARIO="$scenario" \
  -e API_KEY="$api_key" \
  -e RUN_ID="$timestamp-$$" \
  -e CONFIGURED_BATCH="$configured_batch" \
  -e SUMMARY_PATH="/results/$(basename "$summary_path")" \
  -e SUMMARY_MARKDOWN_PATH="/results/$(basename "$summary_markdown_path")" \
  grafana/k6:0.57.0 run --quiet --out "json=/results/$(basename "$raw_path")" /scripts/rate-limiter.js

[[ -s "$summary_path" ]] || { echo "k6 did not create a summary" >&2; exit 1; }
[[ -s "$summary_markdown_path" ]] || { echo "k6 did not create a Markdown summary" >&2; exit 1; }
jq -e '.metrics.checks.values.rate == 1 and (.metrics.unexpected_responses.values.count // 0) == 0' "$summary_path" >/dev/null

curl_api --fail --silent --show-error "$metrics_url" >"$metrics_after_path" 2>/dev/null || : >"$metrics_after_path"
metric_sum() {
  local metric="$1" file="$2"
  awk -v metric="$metric" '$1 ~ ("^" metric "(\\{|$)") {sum += $NF} END {printf "%.0f", sum + 0}' "$file"
}
redis_before="$(metric_sum ratelimiter_redis_calls_total "$metrics_before_path")"
redis_after="$(metric_sum ratelimiter_redis_calls_total "$metrics_after_path")"
hits_before="$(metric_sum ratelimiter_local_cache_hits_total "$metrics_before_path")"
hits_after="$(metric_sum ratelimiter_local_cache_hits_total "$metrics_after_path")"
misses_before="$(metric_sum ratelimiter_local_cache_misses_total "$metrics_before_path")"
misses_after="$(metric_sum ratelimiter_local_cache_misses_total "$metrics_after_path")"
redis_delta=$((redis_after - redis_before))
hit_delta=$((hits_after - hits_before))
miss_delta=$((misses_after - misses_before))
local_hit_ratio="$(awk -v hits="$hit_delta" -v misses="$miss_delta" 'BEGIN {total=hits+misses; if (total == 0) print "0"; else printf "%.6f", hits/total}')"

augmented_summary="$summary_path.tmp"
jq \
  --arg scenario "$scenario" \
  --arg policy "$policy_id" \
  --argjson configuredBatch "$configured_batch" \
  --argjson redisCalls "$redis_delta" \
  --argjson localHits "$hit_delta" \
  --argjson localMisses "$miss_delta" \
  --argjson localHitRatio "$local_hit_ratio" \
  '. + {rateLimiterRun: {scenario: $scenario, policy: $policy, configuredBatch: $configuredBatch, redisCalls: $redisCalls, localCacheHits: $localHits, localCacheMisses: $localMisses, localHitRatio: $localHitRatio}}' \
  "$summary_path" >"$augmented_summary"
mv "$augmented_summary" "$summary_path"

commit_sha="$(git rev-parse --verify HEAD 2>/dev/null || echo uncommitted)"
cpu_count="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo unknown)"
memory_bytes="$(sysctl -n hw.memsize 2>/dev/null || awk '/MemTotal/ {print $2 * 1024}' /proc/meminfo 2>/dev/null || echo unknown)"
{
  echo "commit=$commit_sha"
  echo "date=$(date -u +%FT%TZ)"
  echo "os=$(uname -a)"
  echo "cpu_count=$cpu_count"
  echo "memory_bytes=$memory_bytes"
  echo "containerization=docker-k6-to-compose-network"
  echo "redis_calls_delta=$redis_delta"
  echo "local_cache_hits_delta=$hit_delta"
  echo "local_cache_misses_delta=$miss_delta"
  echo "local_cache_hit_ratio=$local_hit_ratio"
  echo "configured_batch=$configured_batch"
  docker version --format 'docker_client={{.Client.Version}} docker_server={{.Server.Version}}'
  docker compose exec -T redis redis-server --version
  docker compose exec -T app java -version 2>&1
} >"$metadata_path"
{
  echo
  echo "## Runtime evidence"
  echo
  echo "- Commit: \`$commit_sha\`"
  echo "- Redis script calls during run: $redis_delta"
  echo "- Local cache hits during run: $hit_delta"
  echo "- Local cache misses during run: $miss_delta"
  echo "- Local cache hit ratio: $local_hit_ratio"
  echo "- Configured maximum batch: $configured_batch"
  echo "- Environment metadata: \`$(basename "$metadata_path")\`"
} >>"$summary_markdown_path"

echo "k6 result: scenario=$scenario policy=$policy_id rps=$rps vus=$vus duration=$duration keys=$key_cardinality"
jq '{http_reqs: .metrics.http_reqs.values.count, checks_rate: .metrics.checks.values.rate, latency_ms: .metrics.http_req_duration.values, statuses: {allowed: (.metrics.allowed_responses.values.count // 0), denied: (.metrics.denied_responses.values.count // 0), degraded: (.metrics.degraded_responses.values.count // 0), unavailable: (.metrics.unavailable_responses.values.count // 0), unexpected: (.metrics.unexpected_responses.values.count // 0)}}' "$summary_path"
if [[ "$smoke" == "1" ]]; then echo "Smoke benchmark protocol checks passed"; fi
