# Operations runbook

## Start, inspect, and stop

```bash
export COMPOSE_PROJECT_NAME="rate-limiter-ops-$(date +%s)"
docker compose up --build -d
curl --fail http://localhost:8080/actuator/health/liveness
curl --fail http://localhost:8080/actuator/health/readiness
docker compose ps
docker compose logs --since=10m app redis
docker compose --profile observability down -v --remove-orphans
```

The default app health check is liveness, intentionally not readiness, so an orchestrator does not restart a useful fail-open process merely because Redis is down. Readiness includes backend health and should control traffic routing. Do not treat a healthy liveness endpoint as proof that global quotas are enforced.

Production requires `RATE_LIMITER_API_KEY` and `RATE_LIMITER_KEY_HASH_SECRET`; obtain both from a secret manager, not an image, repository, shell history, or ConfigMap. Configure Redis ACL credentials and TLS on private networking. Keep `maxmemory-policy noeviction`, choose persistence/replication/backup recovery objectives, and place a coarse ingress limit ahead of this service.

## Signals and suggested alerts

The dashboard at `http://localhost:3000` covers throughput, allowed/denied/degraded outcomes, p50/p95/p99 decision latency, Redis script p99, errors, calls per decision, cache hit ratio, lease reservations, permit waste, and fallbacks.

Useful PromQL:

```promql
sum by (outcome) (rate(ratelimiter_decisions_total[5m]))
histogram_quantile(0.99, sum by (le) (rate(ratelimiter_decision_duration_seconds_bucket[5m])))
histogram_quantile(0.99, sum by (le) (rate(ratelimiter_redis_script_duration_seconds_bucket[5m])))
sum(rate(ratelimiter_redis_errors_total[5m]))
sum(rate(ratelimiter_redis_calls_total[5m])) / clamp_min(sum(rate(ratelimiter_decisions_total[5m])), 0.001)
sum(rate(ratelimiter_local_cache_hits_total[5m])) / clamp_min(sum(rate(ratelimiter_local_cache_hits_total[5m])) + sum(rate(ratelimiter_local_cache_misses_total[5m])), 0.001)
sum by (failure_mode) (rate(ratelimiter_fallback_activations_total[5m]))
sum(increase(ratelimiter_local_permits_wasted_total[1h]))
```

Alert on sustained fallbacks, readiness down, Redis error/latency growth, decision p99, near-max Redis memory, rejected connections, eviction count greater than zero, replica lag, persistence errors, server-clock offset, hot-key CPU, and lease waste. Rate-limit alert notifications themselves.

## Redis outage or high latency

1. Confirm liveness/readiness separately and inspect bounded `ratelimiter.redis.errors` categories.
2. Check Redis connectivity, ACL/TLS expiration, DNS, connection pool saturation, command latency, CPU, memory, and network loss.
3. Identify which trusted policies are failing open versus closed. Fail-open traffic may be unbounded; apply upstream coarse limits if safe.
4. Do not add an acquisition retry. A timeout may have executed.
5. Restore Redis and observe readiness plus central-decision metrics. No application restart is required.
6. Treat unexpected state reset after recovery as possible restart/failover/replication loss and investigate durability.

## Memory pressure, OOM, and eviction

Redis state always has TTL, but the log consumes one ZSET entry per live permit. Inspect `INFO memory`, `INFO stats`, `DBSIZE`, keyspace notifications if deliberately enabled, and slowlog without exposing digests externally. `evicted_keys > 0` means quotas may have reset and over-admitted. Restore `noeviction`, add capacity or reduce strict-log traffic/limits, and investigate mass-expiry cleanup. Do not change to an eviction policy as a rate-limiter relief valve.

Caffeine eviction wastes already charged permits. Rising `ratelimiter.local.permits.wasted` can indicate an undersized cache, overly long/large batches, churn, or cold cardinality. Reduce batches/TTLs or increase bounded cache capacity after re-evaluating memory and the `M(B-1)` budget.

## `NOSCRIPT` and script failures

`SCRIPT FLUSH` and a new Redis primary may cause `NOSCRIPT`; the client safely sends one `EVAL` because the failed lookup did not execute. A sustained error indicates permissions that forbid script execution, bad deployment artifacts, or connectivity. A Lua runtime error or `WRONGTYPE` is not availability: preserve the key for investigation, capture sanitized logs, and fix the conflicting writer/configuration. Scripts validate types before mutation, so wrong-type failures should not partially update state.

## Hot keys and mass expiry

One digest runs on one Redis primary and scripts serialize. Look for elevated script duration, Redis single-thread CPU, connection queues, and one policy dominating call rate. For token/counter, consider a larger lease batch only inside a quantified error budget. For log, reduce live limit/window if the product requirement permits, isolate the shard, or choose another algorithm. A burst of ZSET entries expiring together makes cleanup O(expired entries) and can block Redis; reproduce manually with the documented benchmark experiment before changing production.

No amount of Redis Cluster sharding splits one exact key. Stronger hardware/dedicated primaries, explicit bounded batching, hierarchical approximation, or edge enforcement are honest alternatives.

## Rolling changes

- Policy version is part of the digest and intentionally resets state. Deploy one coordinated version; mixed versions create independent budgets.
- HMAC secret changes every digest. Rotate via coordinated blue/green traffic cutover, never an uncoordinated rolling secret change.
- `expectedMaxInstances` is a planning assumption. Before scaling above it, recompute `actual M × (B-1)` and lower batch size if needed.
- App instance shutdown/crash strands its local tail; it is never refunded. Expect temporary underutilization during churn.

## Recovery validation

After an incident, verify readiness, central Redis decision source, fallback rate returning to zero, script latency, no evictions, replica/persistence health, clock offset, and a fresh-key strict policy. Preserve task-owned logs before tearing down a local reproduction. Never print logical keys, API/HMAC secrets, Redis passwords, or raw request bodies in an incident ticket.
