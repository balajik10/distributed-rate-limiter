# Benchmarks

## Reproducible harness

k6 runs from a pinned Docker image; no host k6 install is needed. Raw point data and the machine-readable summary go under gitignored `build/results/`. The script validates scenario, rate, VUs, duration, cardinality, policy ID, and base URL before invoking Docker.

```bash
export COMPOSE_PROJECT_NAME="rate-limiter-bench-$(date +%s)"
export SPRING_PROFILES_ACTIVE=demo,benchmark
docker compose up --build -d
./scripts/benchmark.sh --smoke

SCENARIO=strict-hot-key POLICY_ID=benchmark-token-strict RPS=1000 VUS=100 DURATION=60s KEY_CARDINALITY=1 ./scripts/benchmark.sh
SCENARIO=leased-hot-key POLICY_ID=benchmark-token-leased RPS=1000 VUS=100 DURATION=60s KEY_CARDINALITY=1 ./scripts/benchmark.sh
SCENARIO=mixed-80-20 POLICY_ID=benchmark-token-leased RPS=1000 VUS=100 DURATION=60s KEY_CARDINALITY=1000 ./scripts/benchmark.sh
SCENARIO=algorithms POLICY_ID=benchmark-token-strict RPS=300 VUS=50 DURATION=60s KEY_CARDINALITY=100 ./scripts/benchmark.sh
```

Supported environment inputs are `BASE_URL`, `POLICY_ID`, `KEY_CARDINALITY`, `RPS`, `VUS`, `DURATION`, `SCENARIO`, and `API_KEY`. For `mixed-80-20`, cardinality includes one hot key and therefore must be at least two; the remaining slots form the independently indexed cold-key pool. GitHub's manual workflow exposes only allowlisted scenarios/policies and repeats numeric validation.

## Reported measurements

The generated JSON and Markdown summaries report offered configuration, effective key cardinality, configured maximum batch, completed HTTP requests/RPS, allowed/429/degraded-200/503/unexpected counts, check rate, and p50/p95/p99/max HTTP duration. The script records Redis-call and cache-hit/miss deltas plus the local-hit ratio, commit SHA, UTC date, OS, CPU count, memory, Java, Redis, and Docker versions. Raw Prometheus snapshots are retained beside the summary for auditability.

Intentional 429 and 503 responses are registered as expected HTTP statuses and tracked by custom counters; only protocol/schema errors or unexpected statuses fail the smoke gate. Shared-runner latency is not a performance gate.

No throughput or latency number is checked into this document because hardware and load conditions dominate and fabricated/static numbers age badly. Publish a Markdown result only by transforming the raw summary and environment metadata from a real run.

## Interpreting lease results

For successful leased unit acquisitions while quota remains, Redis calls can trend toward requests divided by effective batch `R/B`. Adaptive ramp-up starts at one, so short/cold runs will show less reduction. Denied traffic still reaches Redis because this version has no denial cache. Batch size is a correctness/error-budget input, not a free throughput knob.

Compare strict and leased policies with matched capacity/rate or limit/window and failure mode. Use stable service/Redis resources, warm both paths, record actual cache hit/reservation/waste metrics, and repeat runs. Treat timing-sensitive integration ratios as observations; deterministic controlled-backend tests enforce call-count and lease invariants.

## Manual experiments

### Redis failure

Run a steady scenario, stop only `$COMPOSE_PROJECT_NAME`'s Redis with `docker compose stop redis`, and record status mix, liveness/readiness, fallback metrics, and recovery. Do not call it a limiter-throughput benchmark; fail-open traffic is unbounded degraded behavior.

### Sliding-log mass expiry

Use a dedicated long-limit log policy and one unique hot key. Create a synchronized burst, wait until its exact window boundary, then trigger cleanup while recording Redis `SLOWLOG`, script duration, CPU, and event count. Run only against task-owned development Redis; the cleanup intentionally stresses its single event loop.

### Opt-in 100k-RPS stress

Set `RPS=100000` only on suitable dedicated hardware after raising VUs and matched quota. Record dropped iterations, network/application saturation, Redis single-thread CPU, script latency, command rate, and status mix. One hot key cannot be split across cluster shards; failure at this rate is an expected capacity finding, not a correctness defect. The project does not claim 100k RPS.

## Cleanup

```bash
docker compose --profile observability down -v --remove-orphans
```

Capture task-owned logs before teardown on failure. Never stop unrelated containers or delete unrelated volumes.
