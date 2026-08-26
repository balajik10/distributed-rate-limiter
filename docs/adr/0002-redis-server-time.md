# ADR 0002: Use Redis server time with a monotonic last-seen clamp

- Status: Accepted
- Date: 2026-08-26

## Context

All three algorithms are time dependent. If application instances send their wall-clock values, clock skew lets identical requests refill a bucket, expire log events, or rotate counter buckets differently depending on which node reaches Redis. NTP adjustments and virtualized clocks can also move backward. A Redis failover may expose a host with a different clock offset.

Local lease expiry has a related but different requirement: elapsed time inside one JVM must not be affected by wall-clock corrections or network latency.

## Decision

Every production Lua script calls Redis `TIME` exactly once and converts it to integer milliseconds. Each algorithm persists `last_ms` with its state and calculates:

```text
effectiveNowMs = max(redisNowMs, storedLastMs)
```

All refill, cutoff, rotation, retry, reset, and TTL calculations use that effective time. The application never supplies its wall clock as distributed decision time. Normal responses derive `resetAt` from the script's effective epoch plus its reset delay.

Local lease deadlines use a monotonic Caffeine ticker. The deadline begins immediately before the Redis call and is capped by both the configured lease TTL and `reservationValidForMs` returned by the script. Measuring from call start prevents response latency from extending Redis's authorization horizon.

## Consequences

Application-node clock skew cannot create distributed quota or rotate state inconsistently. A backward Redis clock movement cannot move an existing subject's state backward; time-based progress pauses until server time catches up with the stored value.

The clamp does not make physical time perfect. A large forward Redis-clock jump can refill, expire, or rotate early. A backward jump may temporarily freeze refill or expiry calculations, and a newly promoted replica may have a different offset. Redis hosts therefore require time synchronization, offset alerts, and failover review. The persisted last timestamp is part of every algorithm's state validation and TTL-bound schema.

## Alternatives considered

- Application wall-clock timestamps: rejected because independently skewed nodes would influence one shared limit.
- Application monotonic time for Redis state: rejected because monotonic clocks have no shared epoch across processes or restarts.
- A separate time service: rejected because it adds latency and another availability dependency without improving Redis-local serialization.
- Redis time without `last_ms`: rejected because a backward host step or failover could refill or rotate state backward.
