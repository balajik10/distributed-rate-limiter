package dev.ratelimiter.core;

import java.time.Duration;
import java.util.Objects;

/** An exact rolling-window policy backed by one log entry per reserved permit. */
public record SlidingWindowLogPolicy(
    String id,
    long version,
    int limit,
    Duration window,
    FailureMode failureMode,
    LocalCacheSettings localCacheSettings)
    implements RateLimitPolicy {

  public SlidingWindowLogPolicy {
    RateLimitConstraints.requirePolicyId(id);
    RateLimitConstraints.requirePolicyVersion(version);
    RateLimitConstraints.requireRange(
        "limit", limit, 1, RateLimitConstraints.MAX_SLIDING_LOG_LIMIT);
    RateLimitConstraints.requireAlgorithmDuration("window", window);
    Objects.requireNonNull(failureMode, "failureMode must not be null");
    Objects.requireNonNull(localCacheSettings, "localCacheSettings must not be null");

    validateSlidingValues(limit, window, localCacheSettings);
  }

  @Override
  public Algorithm algorithm() {
    return Algorithm.SLIDING_WINDOW_LOG;
  }

  static void validateSlidingValues(
      int limit, Duration window, LocalCacheSettings localCacheSettings) {
    long windowMillis = window.toMillis();
    RateLimitConstraints.requireProductBelowLuaLimit("limit * windowMs", limit, windowMillis);
    if (localCacheSettings.enabled()) {
      if (localCacheSettings.maxLeaseSize() > limit) {
        throw new IllegalArgumentException("maxLeaseSize must not exceed the sliding-window limit");
      }
      long maximumLeaseTtlMillis = windowMillis / 10L;
      if (localCacheSettings.maxLeaseTtl().toMillis() > maximumLeaseTtlMillis) {
        throw new IllegalArgumentException("maxLeaseTtl must not exceed one tenth of the window");
      }
    }
  }
}
