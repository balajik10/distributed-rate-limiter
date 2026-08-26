#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
for tool in bash curl jq docker git java; do command -v "$tool" >/dev/null 2>&1 || { echo "Required tool not found: $tool" >&2; exit 2; }; done

generated_project="distributed-rate-limiter-verify-$(date +%s)-$$"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-$generated_project}"
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
docker compose build
docker compose up -d

ready=0
for _ in {1..90}; do
  if curl --fail --silent --show-error http://localhost:8080/actuator/health/readiness >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
[[ "$ready" == "1" ]] || { docker compose logs --no-color app redis >&2; exit 1; }

curl --fail --silent --show-error http://localhost:8080/v3/api-docs | jq -e '.openapi and .paths["/api/v1/rate-limits/check"]' >/dev/null
./scripts/demo.sh
./scripts/demo-failure-modes.sh
./scripts/benchmark.sh --smoke

docker compose --profile observability up -d
curl --fail --silent --show-error http://localhost:8080/actuator/prometheus | grep ratelimiter >/dev/null

prometheus_up=0
grafana_up=0
grafana_datasource_up=0
grafana_dashboard_up=0
for _ in {1..60}; do
  if curl --fail --silent --show-error http://localhost:9090/-/ready >/dev/null 2>&1 \
    && [[ "$(curl --silent --show-error 'http://localhost:9090/api/v1/query?query=up%7Bjob%3D%22rate-limiter%22%7D' | jq -r '.data.result[0].value[1] // "0"')" == "1" ]]; then prometheus_up=1; fi
  if curl --fail --silent --show-error http://localhost:3000/api/health | jq -e '.database == "ok"' >/dev/null 2>&1; then
    grafana_up=1
    if curl --fail --silent --show-error http://localhost:3000/api/datasources/uid/prometheus \
      | jq -e '.uid == "prometheus" and .type == "prometheus"' >/dev/null 2>&1; then grafana_datasource_up=1; fi
    if curl --fail --silent --show-error http://localhost:3000/api/dashboards/uid/distributed-rate-limiter \
      | jq -e '.dashboard.uid == "distributed-rate-limiter" and .dashboard.title == "Distributed Rate Limiter"' >/dev/null 2>&1; then grafana_dashboard_up=1; fi
  fi
  [[ "$prometheus_up" == "1" && "$grafana_up" == "1" && "$grafana_datasource_up" == "1" && "$grafana_dashboard_up" == "1" ]] && break
  sleep 1
done
[[ "$prometheus_up" == "1" && "$grafana_up" == "1" && "$grafana_datasource_up" == "1" && "$grafana_dashboard_up" == "1" ]]
curl --fail --silent --show-error http://localhost:3000/api/datasources/uid/prometheus \
  | jq -e '.uid == "prometheus" and .type == "prometheus"' >/dev/null
curl --fail --silent --show-error http://localhost:3000/api/dashboards/uid/distributed-rate-limiter \
  | jq -e '.dashboard.uid == "distributed-rate-limiter" and .dashboard.title == "Distributed Rate Limiter"' >/dev/null

docker compose exec -T app sh -c 'test "$(id -u)" != "0"'
git diff --check
failed=0
echo "All local verification checks passed for Compose project $COMPOSE_PROJECT_NAME"
