# ADR 0001: Use handwritten Redis Lua for atomic state transitions

- Status: Accepted
- Date: 2026-08-26

## Context

One rate-limit decision is a read-modify-write transaction over shared distributed state. Token bucket must refill and debit fixed-point balance; sliding log must validate, remove expired events, count, reserve unique events, and expire two keys; sliding counter must rotate buckets, estimate weighted usage, reserve, and persist. Multiple application instances can execute these transitions concurrently.

A client-side `GET`, calculation, and `SET` loses updates. For example, two instances can both read one remaining permit, both allow, and both write zero. Two admissions occurred while only one debit survived. A JVM lock cannot coordinate other processes. Plain `MULTI/EXEC` does not make a decision based on a queued read, while `WATCH` adds conflict retries and round trips on the hottest keys.

## Decision

Each algorithm uses one handwritten classpath Lua resource for its complete transition. The script:

- receives every Redis key through `KEYS` and keeps multi-key state in one Cluster hash slot;
- validates arguments, key types, stored shape, and numeric bounds before its first write, because Lua errors do not roll back earlier writes;
- calls Redis `TIME` once, then performs cleanup/refill/rotation, decision, reservation, persistence, and expiry atomically;
- uses integer arithmetic strictly below `2^53-1` and bounds per-call work, including a maximum grant of 100;
- accepts `minimumPermits` and `desiredPermits`, allowing strict acquisition and charge-ahead reservation through the same state transition; and
- returns a common seven-integer tuple that Java decodes and validates before creating a public decision.

Scripts are represented by singleton `DefaultRedisScript` objects. Normal execution uses `EVALSHA`; after a definite `NOSCRIPT`, Spring Data Redis may send one `EVAL` fallback. The application does not retry an acquisition after a command timeout because execution may already have committed.

## Consequences

The executing Redis primary provides one linearization order for a subject across all application instances. Cleanup, decision, multi-permit charging, and TTL cannot interleave with another command for that subject.

The scripts must remain short and bounded because Redis serializes their execution. Sliding-log cleanup is still proportional to expired entries and can block a hot shard. Script state schemas and return contracts require dedicated boundary, corruption, concurrency, and `SCRIPT FLUSH` tests.

Atomic execution is not synchronous replication durability. Eviction, restart, persistence loss, or promotion of a lagging replica can restore quota. Multi-key scripts also require the shared digest hash tag when deployed on Redis Cluster.

## Alternatives considered

- Client-side read/modify/write: rejected because it over-admits under concurrency.
- A JVM mutex: rejected because it cannot coordinate multiple application instances.
- `WATCH`/`MULTI` optimistic transactions: feasible, but rejected because contention creates retries, extra round trips, and harder non-idempotent timeout behavior.
- A third-party rate-limiter implementation or Redis module: rejected to keep algorithm semantics, state, and approximation guarantees explicit and auditable in this project.
