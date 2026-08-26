package dev.ratelimiter.core;

import java.time.Duration;
import java.util.Objects;

/** A continuously refilled token-bucket policy. The bucket starts full. */
public record TokenBucketPolicy(
    String id,
    long version,
    int capacity,
    int refillTokens,
    Duration refillPeriod,
    FailureMode failureMode,
    LocalCacheSettings localCacheSettings)
    implements RateLimitPolicy {

  public TokenBucketPolicy {
    RateLimitConstraints.requirePolicyId(id);
    RateLimitConstraints.requirePolicyVersion(version);
    RateLimitConstraints.requireRange("capacity", capacity, 1, RateLimitConstraints.MAX_LIMIT);
    RateLimitConstraints.requireRange(
        "refillTokens", refillTokens, 1, RateLimitConstraints.MAX_LIMIT);
    RateLimitConstraints.requireAlgorithmDuration("refillPeriod", refillPeriod);
    Objects.requireNonNull(failureMode, "failureMode must not be null");
    Objects.requireNonNull(localCacheSettings, "localCacheSettings must not be null");

    long refillPeriodMillis = refillPeriod.toMillis();
    long capacityUnits =
        RateLimitConstraints.requireProductBelowLuaLimit(
            "capacity * refillPeriodMs", capacity, refillPeriodMillis);
    RateLimitConstraints.requireProductBelowLuaLimit(
        "maximum request permit cost",
        RateLimitConstraints.MAX_REQUEST_PERMITS,
        refillPeriodMillis);

    long emptyBucketRefillMillis = positiveCeilingDivision(capacityUnits, refillTokens);
    if (emptyBucketRefillMillis
        > RateLimitConstraints.MAX_EMPTY_BUCKET_REFILL_DURATION.toMillis()) {
      throw new IllegalArgumentException("time to refill an empty bucket must not exceed 30 days");
    }
    RateLimitConstraints.safeAddBelowLuaLimit(
        "maximum token reset delay with expiry padding", emptyBucketRefillMillis, 1_000L);
    if (localCacheSettings.enabled() && localCacheSettings.maxLeaseSize() > capacity) {
      throw new IllegalArgumentException("maxLeaseSize must not exceed token bucket capacity");
    }
  }

  @Override
  public Algorithm algorithm() {
    return Algorithm.TOKEN_BUCKET;
  }

  @Override
  public int limit() {
    return capacity;
  }

  private static long positiveCeilingDivision(long dividend, long divisor) {
    return dividend == 0 ? 0 : ((dividend - 1) / divisor) + 1;
  }
}
