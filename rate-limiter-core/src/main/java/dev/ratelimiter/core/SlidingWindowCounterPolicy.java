package dev.ratelimiter.core;

import java.time.Duration;
import java.util.Objects;

/** A bounded-memory, two-bucket interpolated sliding-window policy. */
public record SlidingWindowCounterPolicy(
    String id,
    long version,
    int limit,
    Duration window,
    FailureMode failureMode,
    LocalCacheSettings localCacheSettings)
    implements RateLimitPolicy {

  public SlidingWindowCounterPolicy {
    RateLimitConstraints.requirePolicyId(id);
    RateLimitConstraints.requirePolicyVersion(version);
    RateLimitConstraints.requireRange("limit", limit, 1, RateLimitConstraints.MAX_LIMIT);
    RateLimitConstraints.requireAlgorithmDuration("window", window);
    Objects.requireNonNull(failureMode, "failureMode must not be null");
    Objects.requireNonNull(localCacheSettings, "localCacheSettings must not be null");

    SlidingWindowLogPolicy.validateSlidingValues(limit, window, localCacheSettings);
  }

  @Override
  public Algorithm algorithm() {
    return Algorithm.SLIDING_WINDOW_COUNTER;
  }
}
