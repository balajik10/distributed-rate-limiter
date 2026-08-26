# Failure modes

## Decision matrix

| Condition | Cache state | `FAIL_OPEN` policy | `FAIL_CLOSED` policy |
|---|---|---|---|
| Redis healthy | live charged tail | consume locally | consume locally |
| Redis healthy | no tail | Lua central decision | Lua central decision |
| Connection refused/reset | live charged tail | consume until deadline | consume until deadline |
| Connection refused/reset | no tail | degraded allow, unknown quota/reset | degraded backend-unavailable denial |
| Command timeout | no tail | degraded allow; execution may be ambiguous | degraded unavailable; execution may be ambiguous |
| `NOSCRIPT` | any | Spring Data Redis safely falls back once to `EVAL` | same |
| Wrong key type, Lua error, malformed result, serialization/config bug | any | internal error; never fallback | internal error; never fallback |
| Redis restart/failover loses state | any | subsequent central state may reset | same |

The HTTP service maps genuine quota denial to 429 and fail-closed backend unavailability to 503. Fail-open returns 200 with `degraded=true`, `remaining=-1`, no reset, source `FAIL_OPEN`, and reason `BACKEND_UNAVAILABLE_FAIL_OPEN`. Fail closed returns no granted permits, `remaining=-1`, no reset, source `FAIL_CLOSED`, and reason `BACKEND_UNAVAILABLE_FAIL_CLOSED`.

## Why policies differ

`api-standard` and `search-default` favor service availability; a temporary burst during Redis loss is less damaging than rejecting all traffic, so they fail open. `login-strict` protects authentication abuse, where losing the control is worse than temporary unavailability, so it fails closed. OTP, payment, expensive writes, and hard abuse controls should generally use fail closed.

The choice is explicit in every immutable trusted policy. A global fallback would erase business context and make an outage silently change security posture.

## Availability classification

Fallback is limited to Lettuce connection failures and command timeouts, including known Spring wrapper/cause chains. A general `RedisSystemException` is not enough: it can wrap Lua runtime errors, `WRONGTYPE`, serialization defects, and other programming failures. Unknown errors remain visible as 500/internal failures and increment a bounded sanitized category.

There is no independent per-node fallback counter. Such counters neither preserve a global quota nor provide a useful bound when the node count changes. Pure fail-open outage traffic can be unbounded.

## Ambiguous timeout and `NOSCRIPT`

An acquisition timeout says only that the caller did not receive the response. Redis may have run Lua and charged the reservation. Automatically retrying can double-charge, while caching an assumed grant can allow uncharged traffic. The implementation applies the configured fallback, records `AMBIGUOUS_EXECUTION`, and does neither.

`EVALSHA` returning `NOSCRIPT` is different: Redis proves that script body did not execute. Spring Data Redis's normal single `EVAL` fallback is safe; no additional application retry is wrapped around acquisition. `SCRIPT FLUSH` therefore causes a transparent reload rather than fallback.

## Startup, health, and recovery

The policy registry is local YAML and does not depend on Redis. If Redis is already unavailable, the app still starts, liveness is up, readiness is down, and acquisitions take their configured fallback paths. Recovery needs no restart; the next request uses Redis once the connection factory succeeds and readiness returns up.

After a primary switch, stored `last_ms` clamps a backward server-clock movement. Redis still requires synchronized hosts. A failover/restart can lose asynchronously replicated or nonpersistent state, effectively resetting quota. Atomicity is primary-local, not a durability guarantee.

The deterministic `scripts/demo-failure-modes.sh` uses fresh no-tail keys, stops only the active Compose project's Redis, proves both policy outcomes and health semantics, restores Redis in a trap, and verifies recovery.
