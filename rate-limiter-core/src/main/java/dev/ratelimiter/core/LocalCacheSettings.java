package dev.ratelimiter.core;

import java.time.Duration;

/** Immutable charge-ahead local permit-leasing settings for one policy. */
public record LocalCacheSettings(
    boolean enabled,
    int maxLeaseSize,
    Duration maxLeaseTtl,
    int expectedMaxInstances,
    long maxErrorPermits) {

  public LocalCacheSettings {
    RateLimitConstraints.requireRange(
        "maxLeaseSize", maxLeaseSize, 1, RateLimitConstraints.MAX_LEASE_SIZE);
    RateLimitConstraints.requireLeaseDuration(maxLeaseTtl);
    if (expectedMaxInstances <= 0) {
      throw new IllegalArgumentException("expectedMaxInstances must be positive");
    }
    RateLimitConstraints.requireLuaInteger("maxErrorPermits", maxErrorPermits, 0);

    if (!enabled) {
      if (maxLeaseSize != 1) {
        throw new IllegalArgumentException("disabled local caching requires maxLeaseSize=1");
      }
      if (maxErrorPermits != 0) {
        throw new IllegalArgumentException("disabled local caching requires maxErrorPermits=0");
      }
    } else {
      long timingShift =
          RateLimitConstraints.requireProductBelowLuaLimit(
              "expectedMaxInstances * (maxLeaseSize - 1)", expectedMaxInstances, maxLeaseSize - 1L);
      if (timingShift > maxErrorPermits) {
        throw new IllegalArgumentException(
            "maxLeaseSize exceeds the configured maxErrorPermits budget for expectedMaxInstances");
      }
    }
  }

  /** Exact, non-leasing settings suitable for policies that disable the local tier. */
  public static LocalCacheSettings disabled() {
    return new LocalCacheSettings(false, 1, Duration.ofMillis(1), 1, 0);
  }

  /** Maximum cross-boundary timing discrepancy under the configured instance assumption. */
  public long plannedTimingErrorPermits() {
    return (long) expectedMaxInstances * (maxLeaseSize - 1L);
  }
}
