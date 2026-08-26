package dev.ratelimiter.service.api;

import dev.ratelimiter.core.Algorithm;
import dev.ratelimiter.core.DecisionReason;
import dev.ratelimiter.core.DecisionSource;
import dev.ratelimiter.core.RateLimitDecision;

public record RateLimitCheckResponse(
    boolean allowed,
    String policyId,
    long policyVersion,
    Algorithm algorithm,
    int limit,
    long remaining,
    int grantedPermits,
    long retryAfterMs,
    Long resetAtEpochMs,
    DecisionSource source,
    DecisionReason reason,
    boolean approximate,
    boolean degraded,
    String requestId) {

  public static RateLimitCheckResponse from(RateLimitDecision decision, String requestId) {
    Long resetAtEpochMs = decision.resetAt() == null ? null : decision.resetAt().toEpochMilli();
    return new RateLimitCheckResponse(
        decision.allowed(),
        decision.policyId(),
        decision.policyVersion(),
        decision.algorithm(),
        decision.limit(),
        decision.remaining(),
        decision.grantedPermits(),
        decision.retryAfterMillis(),
        resetAtEpochMs,
        decision.source(),
        decision.reason(),
        decision.approximate(),
        decision.degraded(),
        requestId);
  }
}
