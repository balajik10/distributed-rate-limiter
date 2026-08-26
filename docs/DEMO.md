# Five-minute demo

## 0:00–1:00 — start and frame

```bash
export COMPOSE_PROJECT_NAME="rate-limiter-demo-$(date +%s)"
docker compose up --build -d
curl --fail http://localhost:8080/actuator/health/readiness | jq
```

Pitch: three selectable distributed algorithms share a Spring-independent API. Every central decision is an atomic handwritten Lua transition using Redis server time. Optional Caffeine leases charge Redis first and bound timing error; failure posture is trusted-policy-specific.

## 1:00–2:00 — inspect policies and contracts

```bash
curl http://localhost:8080/api/v1/policies | jq
open http://localhost:8080/swagger-ui/index.html  # macOS; otherwise open the URL manually
```

Point out `api-standard` token bucket/fail open/leased, `login-strict` exact log/fail closed/no lease, and `search-default` counter/fail open/leased. The client cannot change those fields. Logical keys never appear in policy output, responses, metrics, or Redis key names.

## 2:00–3:00 — deterministic correctness

```bash
./scripts/demo.sh
```

The script uses unique keys, exercises all algorithms, validates response schema and headers, and makes six login calls. Exactly five return 200 and the sixth returns 429 with meaningful reset/retry data. It exits nonzero on any mismatch.

Explain why a Java `GET`/`SET` pair loses updates under concurrent servers, while Lua linearizes clean/refill/decide/reserve/write/expire. Mention the strict concurrency tests that synchronize ten independent clients and admit exactly the central quota.

## 3:00–4:00 — outage policy

```bash
./scripts/demo-failure-modes.sh
```

The script warms the local registry, chooses fresh no-tail subjects, stops only this Compose project's Redis, and proves:

- `api-standard` returns 200 with `source=FAIL_OPEN`, degraded/approximate, unknown remaining/reset;
- `login-strict` returns 503 with `source=FAIL_CLOSED` and no granted permit;
- liveness stays 200 while readiness becomes 503;
- Redis is restored by a trap and readiness recovers.

An ambiguous timeout is never automatically retried. Already charged local tails may survive only to their short monotonic deadline.

## 4:00–5:00 — bound and observability

```bash
docker compose --profile observability up -d
open http://localhost:3000
./scripts/benchmark.sh --smoke
```

For actual live nodes `M` and maximum batch `B`, each node can shift at most `B-1` already charged tail permits across a boundary: cache discrepancy `<=M(B-1)`. Normal uncharged admissions are zero. The counter's separate model can approach `2L`; fail-open traffic is outside both bounds.

Close with the honest hot-key constraint: one exact state maps to one Redis primary and serialized Lua. Cluster scales different keys, not one exact key. Tear down only the generated project:

```bash
docker compose --profile observability down -v --remove-orphans
```
