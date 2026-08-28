#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

for tool in curl docker jq npm; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "Required tool not found: $tool" >&2
    exit 2
  }
done

generated_project="rate-limiter-ui-e2e-$(date +%s)-$$"
project_name="${COMPOSE_PROJECT_NAME:-$generated_project}"
if [[ ! "$project_name" =~ ^rate-limiter(-ui)?-e2e-[A-Za-z0-9][A-Za-z0-9_.-]*$ ]]; then
  echo "Refusing destructive E2E lifecycle for unrecognized project name: $project_name" >&2
  echo "Use a unique name beginning with rate-limiter-e2e- or rate-limiter-ui-e2e-." >&2
  exit 2
fi

export COMPOSE_PROJECT_NAME="$project_name"
export REDIS_PORT="${REDIS_PORT:-16379}"
export APP_PORT="${APP_PORT:-18080}"
export UI_HOST_PORT="${UI_HOST_PORT:-13001}"
export PROMETHEUS_PORT="${PROMETHEUS_PORT:-19090}"
export GRAFANA_PORT="${GRAFANA_PORT:-13000}"

app_base_url="${BASE_URL:-http://127.0.0.1:$APP_PORT}"
ui_base_url="${UI_BASE_URL:-http://127.0.0.1:$UI_HOST_PORT}"
prometheus_base_url="${PROMETHEUS_BASE_URL:-http://127.0.0.1:$PROMETHEUS_PORT}"
grafana_base_url="${GRAFANA_BASE_URL:-http://127.0.0.1:$GRAFANA_PORT}"

export BASE_URL="$app_base_url"
export OPENAPI_BASE_URL="${OPENAPI_BASE_URL:-$app_base_url}"
export UI_BASE_URL="$ui_base_url"
export PROMETHEUS_BASE_URL="$prometheus_base_url"
export GRAFANA_BASE_URL="$grafana_base_url"
export E2E_COMPOSE_PROJECT_NAME="$project_name"

failed=1
cleanup() {
  set +e
  if [[ "$failed" == "1" ]]; then
    mkdir -p build/results
    docker compose --profile observability logs --no-color \
      >build/results/compose-ui-e2e.log 2>&1
  fi
  docker compose --profile observability down -v --remove-orphans >/dev/null 2>&1
  set -e
}
trap cleanup EXIT

wait_for_url() {
  local url="$1"
  local attempts="$2"
  for ((attempt = 1; attempt <= attempts; attempt += 1)); do
    if curl --fail --silent --show-error "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

docker compose config --quiet
docker compose --profile observability config --quiet
default_services="$(docker compose config --services)"
grep -Fx ui <<<"$default_services" >/dev/null
if grep -Eq '^(prometheus|grafana)$' <<<"$default_services"; then
  echo "Observability services unexpectedly appeared in the default profile" >&2
  exit 1
fi

docker compose up --build -d redis app ui
if ! wait_for_url "$app_base_url/actuator/health/readiness" 120 \
  || ! wait_for_url "$ui_base_url/healthz" 30; then
  docker compose logs --no-color app ui redis >&2
  exit 1
fi

curl --fail --silent --show-error "$ui_base_url/console" | grep -qi '<!doctype html>'
curl --fail --silent --show-error "$ui_base_url/api/v1/policies" | jq -e 'length >= 3' >/dev/null

(
  cd rate-limiter-ui
  npm run api:verify-live
)

./scripts/demo.sh

docker compose --profile observability up -d prometheus grafana
if ! wait_for_url "$prometheus_base_url/-/ready" 90 \
  || ! wait_for_url "$grafana_base_url/api/health" 90; then
  docker compose --profile observability logs --no-color prometheus grafana >&2
  exit 1
fi

observability_ready=0
for _attempt in {1..90}; do
  if prometheus_payload="$(
    curl --fail --silent --show-error \
      "$prometheus_base_url/api/v1/query?query=up%7Bjob%3D%22rate-limiter%22%7D" 2>/dev/null
  )"; then
    prometheus_up="$(jq -r '.data.result[0].value[1] // "0"' <<<"$prometheus_payload")"
  else
    prometheus_up=""
  fi
  if datasource_payload="$(
    curl --fail --silent --show-error "$grafana_base_url/api/datasources/uid/prometheus" \
      2>/dev/null
  )"; then
    datasource_up="$(
      jq -r 'select(.uid == "prometheus" and .type == "prometheus") | .uid' \
        <<<"$datasource_payload"
    )"
  else
    datasource_up=""
  fi
  if dashboard_payload="$(
    curl --fail --silent --show-error \
      "$grafana_base_url/api/dashboards/uid/distributed-rate-limiter" 2>/dev/null
  )"; then
    dashboard_up="$(
      jq -r 'select(.dashboard.uid == "distributed-rate-limiter") | .dashboard.uid' \
        <<<"$dashboard_payload"
    )"
  else
    dashboard_up=""
  fi
  if [[ "$prometheus_up" == "1" && "$datasource_up" == "prometheus" \
    && "$dashboard_up" == "distributed-rate-limiter" ]]; then
    observability_ready=1
    break
  fi
  sleep 1
done
if [[ "$observability_ready" != "1" ]]; then
  echo "Observability provisioning did not become ready in time" >&2
  exit 1
fi

(
  cd rate-limiter-ui
  npm run test:e2e
)

docker compose exec -T app sh -c 'test "$(id -u)" != "0"'
docker compose exec -T ui sh -c 'test "$(id -u)" != "0"'

failed=0
echo "Real-stack UI E2E passed for isolated Compose project $project_name"
