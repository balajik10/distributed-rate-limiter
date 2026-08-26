# Algorithms

## Common script contract

Every acquisition is one handwritten Lua transition. A script validates numeric arguments, arity, and existing Redis key types before its first mutation, calls `TIME` once, and calculates integer milliseconds. It uses `effectiveNowMs = max(redisNowMs, storedLastMs)`, integer arithmetic below `2^53 - 1`, and keys supplied only through `KEYS`.

Inputs distinguish `minimumPermits` from `desiredPermits`. A strict call sets both to the caller's permits. A leased unit call sends `minimum=1` and `desired=B`; this reserves at most `B`, never `B+1`. A successful grant `g` obeys `minimum <= g <= desired`.

All scripts return:

```text
[allowed, reservedPermits, centralRemaining, retryAfterMs,
 resetAfterMs, reservationValidForMs, effectiveNowEpochMs]
```

On allow, `reservedPermits >= minimum`, retry is zero, and the reservation horizon is positive. On deny, reservation is zero, remaining is less than minimum, retry is at least one millisecond, and the reservation horizon is zero. Java rejects malformed results as programming errors rather than activating fallback.

Positive ceiling division uses `a == 0 ? 0 : floor((a - 1) / b) + 1`, avoiding an overflowing `a + b - 1`. All state receives an algorithm-specific TTL after access; active denied traffic may refresh TTL.

## Token bucket

State is one hash:

```text
balance_units  # one token costs refillPeriodMs units
last_ms
```

With capacity `C`, refill tokens `R`, and period `P` milliseconds:

```text
capacityUnits = C × P
refill = elapsedMs × R
permitCost = permits × P
```

Missing state begins full. Elapsed time is capped at the time needed to fill the current deficit before multiplication, then refill is capped at `capacityUnits`. Available whole permits are `floor(balanceUnits / P)`. If at least the requested minimum exists, the script reserves `min(desired, available)` and subtracts the entire reservation. Otherwise it reserves nothing and returns exact retry `ceil((minimum×P - balanceUnits) / R)`.

After either path it stores the clamped effective time. Reset is `ceil((capacityUnits - postDecisionBalance) / R)` and remaining is whole tokens. The local reservation horizon is time until full; Java caps it by the policy's short lease TTL. TTL is time from empty to full plus one second.

Invariants and edge cases:

- Refill is exact rational fixed point; fractional progress is never discarded.
- Empty-to-full time is capped at 30 days by policy validation.
- Zero refill, malformed/noninteger hashes, unsafe products, and unsafe reset epochs fail before mutation.
- A backward Redis-clock step cannot add tokens because stored `last_ms` wins.
- Admissions over interval `T <= capacity + refillRate × T`; a burst up to capacity is intentional.

Time and memory are O(1). This is not an exact rolling-window limit: a burst near an arbitrary window boundary can exceed a “L per every rolling interval” interpretation.

## Sliding-window log

State uses a ZSET of events and a metadata hash:

```text
<prefix>:{digest}:swl:events  score=effectiveNowMs, member="milliseconds:sequence"
<prefix>:{digest}:swl:meta    last_ms, sequence
```

The active interval is exactly `(now - W, now]`; an event scored at `now - W` is expired. Before cleanup the script validates both key types and metadata. Events without metadata are corruption; metadata without events is valid because metadata intentionally has a longer TTL.

The script removes scores `<= now-W`, counts live events, and computes `available = L-current`. If sufficient it grants at most the desired amount. Events sharing one Redis millisecond use an incrementing sequence, formatted with `string.format("%.0f", value)`, so ZSET members cannot overwrite. The sequence resets when time advances or cleanup leaves the set empty. Grant loops are bounded at 100, and one variadic `ZADD` inserts the batch.

For a denied weighted request `q`, `k = current + q - L`. The zero-based event at index `k-1` is the one whose expiry frees enough room; retry is its score plus `W` minus effective now. Reset uses the newest live event. Denial inserts no event, though expired-state cleanup is allowed. The event key TTL is `W+1s`; metadata is `W+2s`; the reservation horizon is `W` before Java's lease cap.

Insertion is O(g log n), cleanup O(log n + m), and memory O(live events). A large group expiring together can block Redis's single event loop. With leasing disabled or batch one, this is the exact rolling-window implementation.

## Sliding-window counter

State is one hash:

```text
current_start_ms
current_count
previous_count
last_ms
```

Let window `W`, limit `L`, elapsed `e` in the current fixed bucket, previous count `p`, and current count `c`:

```text
bucketStart = floor(now / W) × W
weightedNumerator = p × (W - e) + c × W
capacityNumerator = L × W
available = floor((capacityNumerator - weightedNumerator) / W), clamped to 0..L
```

The same bucket preserves counts. At exactly one elapsed bucket, previous becomes current and current resets. At two or more, both reset. Rotation never moves backward. Before mutation existing counts must be within `0..L` and `current_start_ms <= last_ms < current_start_ms+W`; corruption errors rather than being normalized.

When enough space exists, the full reservation increments current count, then remaining is recomputed from the updated integer numerator. On denial a bounded integer binary search finds the first model-consistent delay in `[1, 2W-e]`:

- Before the next boundary, future numerator is `p × (W-e-d) + c × W`.
- After it, with `y = d-(W-e)`, future numerator is `c × (W-y)`.
- Sufficient means `futureNumerator + requested×W <= L×W`.

The search covers both previous-count decay before the boundary and current-count decay after rotation. Reset is `2W-e` with current count, `W-e` with only previous count, and zero with no state. Reservation validity ends at the bucket after next. State TTL is `2W+1s`. Runtime and memory are O(1), including the bounded logarithmic search over a maximum 24-hour window.

This model is intrinsically approximate even without Caffeine. If `L` events arrive near the end of the previous fixed bucket, nearly another `L` may be admitted early in the next true rolling window; actual events can approach `2L`. The opposite distribution can be substantially over-rejected. Users requiring strict rolling-window semantics choose the log.

## Comparison and selection

| Property | Token bucket | Sliding log | Sliding counter |
|---|---|---|---|
| Distributed transition | Atomic Lua | Atomic Lua | Atomic Lua |
| Redis structures | 1 hash | 1 ZSET + 1 hash | 1 hash |
| Work | O(1) | O(g log n + expired) | O(1) |
| Exact rolling window | No | Yes in strict mode | No |
| Intentional approximation | Burst envelope | Lease timing only | Interpolation + lease timing |
| Suggested use | General APIs/search | Login/OTP/payment | High-volume approximate controls |

For all algorithms, local leasing shifts already charged permits in time. It does not change cumulative normal charging; its separate bound is documented in [CACHE_CONSISTENCY.md](CACHE_CONSISTENCY.md).
