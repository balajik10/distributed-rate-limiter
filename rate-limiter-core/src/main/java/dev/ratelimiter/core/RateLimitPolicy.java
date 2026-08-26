package dev.ratelimiter.core;

/** Trusted, immutable server-side policy definition. */
public sealed interface RateLimitPolicy
    permits TokenBucketPolicy, SlidingWindowLogPolicy, SlidingWindowCounterPolicy {

  String id();

  long version();

  Algorithm algorithm();

  FailureMode failureMode();

  LocalCacheSettings localCacheSettings();

  /** The maximum caller permits available at once: capacity for a token bucket, limit otherwise. */
  int limit();

  /** Alias that mirrors the public request/decision field name. */
  default String policyId() {
    return id();
  }

  /** Alias that mirrors the public decision field name. */
  default long policyVersion() {
    return version();
  }

  /** Validates a request against this policy's algorithm-specific ceiling. */
  default void validatePermits(int permits) {
    RateLimitConstraints.requireRequestPermits(permits);
    if (permits > limit()) {
      throw new PermitsExceedPolicyLimitException(id(), permits, limit());
    }
  }
}
