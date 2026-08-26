# Cache consistency and permit leasing

## Protocol

The local tier is a charge-ahead permit lease, not a cached counter. Redis linearizes and charges a reservation before Java admits or caches any part of it:

1. Only a real unit request may refill; there is no prefetch or background task.
2. Atomically consume a live mapped tail if present.
3. On miss, lock one of 4,096 bounded stripes and recheck the map.
4. Select an adaptive batch `B` from `1 → 2 → 4 → 8 → policy maximum`, growing only when the previous reservation was exhausted inside the two-second ramp window.
5. Call Lua with `minimum=1`, `desired=B`. A multi-permit request bypasses this path and asks Redis for exactly its own amount.
6. Redis charges `g`, the triggering caller consumes one, and at most `g-1` is stored.
7. Deadline is `callStartNanos + min(policy lease TTL, script reservation validity)`. Measuring from call start prevents network latency from extending authorization. If the result arrives after that deadline, the trigger is still charged/served and no tail is cached.
8. Expiry, eviction, policy version change, shutdown, or process loss counts remaining permits as waste and never refunds them.

Cache identity is `(policyId, policyVersion, algorithm, subjectDigest)`, never the raw key. One JVM has at most one live tail per identity. Consumption occurs through a Caffeine map atomic update with deadline checking and decrement inside the mapping operation. A stale object reference therefore cannot spend an expired lease that another thread replaced.

A size-one reservation has no tail. Its bounded ramp-history entry records demand but is not a second live permit lease. Inactivity/expiry resets the next batch to one.

## Why write-behind and refunds are unsafe

“Allow locally, synchronize later” admits permits not yet charged to the global state. Multiple nodes can do so concurrently, and a crash loses unflushed usage; without a durable coordination protocol there is no useful deterministic over-admission bound.

Refunding an expired or evicted tail has a dual race: a consumer may be spending the final permit while another thread refunds it, or Redis may make refunded quota available while an old local reference is still usable. The implementation chooses safe underutilization instead. A lost reservation strands quota temporarily but cannot create an uncharged normal admission.

## Zero uncharged normal admissions

At every normal allow there are two possibilities:

- Redis atomically charged the current strict/grant request before returning; or
- the JVM atomically consumed a permit from a tail that was part of an earlier Redis charge.

No other normal allow path exists. By induction over decisions:

```text
cumulative normal admissions <= cumulative Redis-charged permits
uncharged normal admissions = 0
```

This statement excludes `FAIL_OPEN`. An ambiguous timeout followed by fail open can also admit even when Redis charged the request; that is explicit degraded behavior and is never counted as a cache guarantee.

## Timing-error proof

Let:

```text
M = actual live JVMs that can hold a lease for this exact subject
B = maximum reservation batch
```

Each JVM has only one demand-driven, non-prefetched, pre-boundary reservation. Its triggering request consumed at least one permit at the Redis linearization point, leaving at most `B-1` permits that can be admitted locally after a measurement boundary. Summing across JVMs:

```text
cache cross-boundary timing discrepancy <= M × (B - 1)
```

Therefore, when Redis-backed normal enforcement is operating:

```text
sliding log actual admissions <= L + M × (B - 1)
token bucket admissions over T <= capacity + refillRate×T + M × (B - 1)
sliding counter actual admissions < 2L + M × (B - 1)
```

The nearly `2L` counter term comes from two-bucket interpolation, not Caffeine. When measuring downstream work rather than decision times, `M×B` is the conservative wall-clock shift because even each triggering request may cross the boundary after Redis returns.

The proof fails if the implementation prefetches, holds parallel leases for one JVM/key, refunds, writes behind, or uses a tail after its monotonic deadline. Tests protect single-flight refill, atomic stale-generation rejection, maximum live tail, cumulative charging, and fixed-seed boundary models.

## Error budget and replicas

For configured timing-error budget `E` and expected maximum instances:

```text
B <= floor(E / expectedMaxInstances) + 1
```

Example: `M=10`, `B=10` gives at most `10×9=90` permits of cache timing discrepancy. Batch one or disabled caching gives zero cache timing error. `expectedMaxInstances` is only a design-time assumption; the system does not enforce replica count. The real bound always uses actual live `M`, and deploying more instances invalidates the configured budget.

## Waste versus over-admission

Timing shift and quota waste are different axes. At one instant, the current tail per node/key is bounded. Across a long window, repeated expiry, size eviction, process churn, and deployment replacement can strand much more quota—up to severe/full underutilization. None of those create uncharged normal admissions. Operators monitor current tails, reservations, consumption, and cumulative waste separately.

Already charged tails remain usable through a Redis outage until their short deadline. Once empty/expired, the policy chooses fail open or fail closed. Fail-open outage traffic is unbounded and outside every lease formula.
