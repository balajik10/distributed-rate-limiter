#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
for tool in bash curl jq docker git java; do command -v "$tool" >/dev/null 2>&1 || { echo "Required tool not found: $tool" >&2; exit 2; }; done

generated_project="distributed-rate-limiter-verify-$(date +%s)-$$"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-$generated_project}"
app_port="${APP_PORT:-8080}"
ui_port="${UI_HOST_PORT:-3001}"
prometheus_port="${PROMETHEUS_PORT:-9090}"
grafana_port="${GRAFANA_PORT:-3000}"
base_url="${BASE_URL:-http://127.0.0.1:$app_port}"
ui_base_url="${UI_BASE_URL:-http://127.0.0.1:$ui_port}"
prometheus_base_url="${PROMETHEUS_BASE_URL:-http://127.0.0.1:$prometheus_port}"
grafana_base_url="${GRAFANA_BASE_URL:-http://127.0.0.1:$grafana_port}"
export BASE_URL="$base_url"
failed=1
cleanup() {
  if [[ "$failed" == "1" ]]; then
    mkdir -p build/results
    docker compose --profile observability logs --no-color >build/results/compose-failure.log 2>&1 || true
  fi
  docker compose --profile observability down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

maven_args=(-B -ntp)
if [[ -n "${MAVEN_LOCAL_REPOSITORY:-}" ]]; then
  maven_args+=("-Dmaven.repo.local=$MAVEN_LOCAL_REPOSITORY")
fi
./mvnw "${maven_args[@]}" clean verify
docker compose config --quiet
docker compose config --services | grep -Fx ui >/dev/null
docker compose --profile observability config --quiet
docker compose build
docker compose up -d

ready=0
for _ in {1..90}; do
  if curl --fail --silent --show-error "$base_url/actuator/health/readiness" >/dev/null 2>&1 \
    && curl --fail --silent --show-error "$ui_base_url/healthz" | grep -Fx ok >/dev/null; then ready=1; break; fi
  sleep 1
done
[[ "$ready" == "1" ]] || { docker compose logs --no-color app ui redis >&2; exit 1; }

curl --fail --silent --show-error "$base_url/v3/api-docs" | jq -e '.openapi and .paths["/api/v1/rate-limits/check"]' >/dev/null
curl --fail --silent --show-error "$ui_base_url/console" | grep -qi '<!doctype html>'
curl --fail --silent --show-error "$ui_base_url/api/v1/policies" | jq -e 'length >= 3' >/dev/null
./scripts/demo.sh
./scripts/demo-failure-modes.sh
(
  # k6 runs inside Docker. Keep its request path on the Compose network while
  # the host-side policy and metrics probes use the published application port.
  unset BASE_URL
  export METRICS_URL="${METRICS_URL:-$base_url/actuator/prometheus}"
  export POLICY_URL_BASE="${POLICY_URL_BASE:-$base_url/api/v1/policies}"
  ./scripts/benchmark.sh --smoke
)

docker compose --profile observability up -d
curl --fail --silent --show-error "$base_url/actuator/prometheus" | grep ratelimiter >/dev/null

prometheus_up=0
grafana_up=0
grafana_datasource_up=0
grafana_dashboard_up=0
for _ in {1..60}; do
  if curl --fail --silent --show-error "$prometheus_base_url/-/ready" >/dev/null 2>&1 \
    && [[ "$(curl --silent --show-error "$prometheus_base_url/api/v1/query?query=up%7Bjob%3D%22rate-limiter%22%7D" | jq -r '.data.result[0].value[1] // "0"')" == "1" ]]; then prometheus_up=1; fi
  if curl --fail --silent --show-error "$grafana_base_url/api/health" | jq -e '.database == "ok"' >/dev/null 2>&1; then
    grafana_up=1
    if curl --fail --silent --show-error "$grafana_base_url/api/datasources/uid/prometheus" \
      | jq -e '.uid == "prometheus" and .type == "prometheus"' >/dev/null 2>&1; then grafana_datasource_up=1; fi
    if curl --fail --silent --show-error "$grafana_base_url/api/dashboards/uid/distributed-rate-limiter" \
      | jq -e '.dashboard.uid == "distributed-rate-limiter" and .dashboard.title == "Distributed Rate Limiter"' >/dev/null 2>&1; then grafana_dashboard_up=1; fi
  fi
  [[ "$prometheus_up" == "1" && "$grafana_up" == "1" && "$grafana_datasource_up" == "1" && "$grafana_dashboard_up" == "1" ]] && break
  sleep 1
done
[[ "$prometheus_up" == "1" && "$grafana_up" == "1" && "$grafana_datasource_up" == "1" && "$grafana_dashboard_up" == "1" ]]
curl --fail --silent --show-error "$grafana_base_url/api/datasources/uid/prometheus" \
  | jq -e '.uid == "prometheus" and .type == "prometheus"' >/dev/null
curl --fail --silent --show-error "$grafana_base_url/api/dashboards/uid/distributed-rate-limiter" \
  | jq -e '.dashboard.uid == "distributed-rate-limiter" and .dashboard.title == "Distributed Rate Limiter"' >/dev/null

docker compose exec -T app sh -c 'test "$(id -u)" != "0"'
docker compose exec -T ui sh -c 'test "$(id -u)" != "0"'
git diff --check
failed=0
echo "All local verification checks passed for Compose project $COMPOSE_PROJECT_NAME"
