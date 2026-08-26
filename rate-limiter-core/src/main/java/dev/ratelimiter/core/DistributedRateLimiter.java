package dev.ratelimiter.core;

/** Spring-independent public API for non-idempotent distributed permit acquisition. */
@FunctionalInterface
public interface DistributedRateLimiter {
  RateLimitDecision tryAcquire(RateLimitRequest request);

  default RateLimitDecision tryAcquire(String policyId, String key) {
    return tryAcquire(new RateLimitRequest(policyId, key, 1));
  }
}
