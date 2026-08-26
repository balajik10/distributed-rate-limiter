# ADR 0003: Use charge-ahead local permit leasing

- Status: Accepted
- Date: 2026-08-26

## Context

Strict central acquisition gives the clearest semantics, but every request for a hot subject executes on the same Redis primary. A conventional local counter with asynchronous write-behind admits usage before it is globally charged; concurrent nodes and process failure then make overshoot unbounded. Refunding unused local quota is also unsafe if a final consumer races the refund.

The project needs an optional local fast path with an explicit approximation budget and no uncharged normal admissions.

## Decision

Use demand-driven charge-ahead leases for unit-permit requests:

1. Atomically consume a live Caffeine tail if one exists.
2. On a miss, acquire one of a bounded set of striped locks and recheck the map, providing single-flight refill for the key.
3. Choose an adaptive reservation target that grows `1 → 2 → 4 → 8 → policy maximum` only after rapid exhaustion and resets after inactivity.
4. Ask Redis Lua to grant between `minimum=1` and `desired=B`. Redis charges the full grant before Java returns an allow.
5. Consume one permit for the triggering request and cache at most `reservedPermits-1` until the shorter of policy TTL and script authorization horizon.

Cache identity contains policy ID, policy version, algorithm, and subject digest, never the raw logical key. One JVM may hold at most one live tail per identity. Caffeine map computation validates the monotonic deadline and decrements within the same atomic mapping operation, preventing a retained stale object from spending a replaced lease. Multi-permit requests bypass leasing. There is no prefetch, background refill, write-behind, or refund on expiry, eviction, shutdown, policy change, or crash.

Let `M` be actual live JVMs able to hold a lease for one subject and `B` the maximum batch. Each reservation's triggering request consumes at least one permit at Redis linearization time, so at most `B-1` permits per JVM can be admitted after a later boundary:

```text
uncharged normal admissions = 0
cache cross-boundary timing discrepancy <= M × (B - 1)
sliding log actual admissions <= L + M × (B - 1)
token bucket admissions over T <= C + refillRate×T + M × (B - 1)
sliding counter actual admissions < 2L + M × (B - 1)
```

Configuration enforces `B <= floor(errorBudget / expectedMaxInstances) + 1`. `expectedMaxInstances` is a planning assumption, not replica enforcement; the real bound always uses actual `M`.

## Consequences

Successful hot unit traffic can use local, already charged permits and reduce Redis calls as batches ramp up. Batch one or disabled caching retains strict central mode with zero cache timing shift. Responses with a live tail are marked approximate and retain the central remaining/reset snapshot.

Fail-open traffic and ambiguous-timeout fallback are outside the bound. The counter's nearly `2L` term is intrinsic to its algorithm, not caused by Caffeine. Measuring downstream work rather than limiter decision time may require the more conservative `M×B` boundary shift.

Unused leases cause underutilization. Repeated expiry, size eviction, or process churn can cumulatively strand much more quota than `M(B-1)`; this waste is measured separately from current cached tail where the process remains alive. A hard crash cannot report its final waste. No case refunds and risks double spending.

## Alternatives considered

- Strict Redis acquisition for every request: retained as the cache-disabled/batch-one option, but not the only mode because it maximizes hot-key Redis calls.
- Write-behind local counters: rejected because permits are admitted before charging and crash loss makes overshoot unbounded.
- Prefetched or parallel leases: rejected because they invalidate the one-tail `M(B-1)` proof and waste cold-key quota.
- Refunds on expiry or shutdown: rejected because refund and consumption can race.
- Independent per-node fallback limiters: rejected because they are not equivalent to one global policy and give misleading outage guarantees.
