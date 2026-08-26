package dev.ratelimiter.core;

/** One non-idempotent attempt to consume permits for a policy/key pair. */
public record RateLimitRequest(String policyId, String key, int permits) {
  public RateLimitRequest {
    RateLimitConstraints.requirePolicyId(policyId);
    RateLimitConstraints.requireLogicalKey(key);
    RateLimitConstraints.requireRequestPermits(permits);
  }
}
