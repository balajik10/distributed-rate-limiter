package dev.ratelimiter.core;

import java.time.Instant;
import java.util.Objects;

/** Immutable public result of a permit-acquisition attempt. */
public record RateLimitDecision(
    boolean allowed,
    String policyId,
    long policyVersion,
    Algorithm algorithm,
    int limit,
    long remaining,
    int grantedPermits,
    long retryAfterMillis,
    Instant resetAt,
    DecisionSource source,
    DecisionReason reason,
    boolean approximate,
    boolean degraded) {

  public RateLimitDecision {
    RateLimitConstraints.requirePolicyId(policyId);
    RateLimitConstraints.requirePolicyVersion(policyVersion);
    Objects.requireNonNull(algorithm, "algorithm must not be null");
    RateLimitConstraints.requireRange("limit", limit, 1, RateLimitConstraints.MAX_LIMIT);
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(reason, "reason must not be null");

    if (allowed) {
      RateLimitConstraints.requireRequestPermits(grantedPermits);
      if (grantedPermits > limit) {
        throw new IllegalArgumentException("grantedPermits must not exceed limit");
      }
    } else if (grantedPermits != 0) {
      throw new IllegalArgumentException("a denied decision must grant zero permits");
    }

    boolean fallback = source == DecisionSource.FAIL_OPEN || source == DecisionSource.FAIL_CLOSED;
    if (fallback) {
      validateFallback(
          allowed, remaining, retryAfterMillis, resetAt, source, reason, approximate, degraded);
    } else {
      validateNormal(
          allowed,
          algorithm,
          limit,
          remaining,
          retryAfterMillis,
          resetAt,
          source,
          reason,
          approximate,
          degraded);
    }
  }

  private static void validateFallback(
      boolean allowed,
      long remaining,
      long retryAfterMillis,
      Instant resetAt,
      DecisionSource source,
      DecisionReason reason,
      boolean approximate,
      boolean degraded) {
    if (remaining != -1) {
      throw new IllegalArgumentException("backend-unavailable remaining must be -1");
    }
    if (retryAfterMillis != -1) {
      throw new IllegalArgumentException("backend-unavailable retryAfterMillis must be -1");
    }
    if (resetAt != null) {
      throw new IllegalArgumentException("backend-unavailable resetAt must be null");
    }
    if (!degraded) {
      throw new IllegalArgumentException("backend-unavailable decisions must be degraded");
    }
    if (source == DecisionSource.FAIL_OPEN) {
      if (!allowed || reason != DecisionReason.BACKEND_UNAVAILABLE_FAIL_OPEN || !approximate) {
        throw new IllegalArgumentException("FAIL_OPEN must be an approximate fallback allow");
      }
    } else if (allowed || reason != DecisionReason.BACKEND_UNAVAILABLE_FAIL_CLOSED || approximate) {
      throw new IllegalArgumentException("FAIL_CLOSED must be a non-approximate fallback denial");
    }
  }

  private static void validateNormal(
      boolean allowed,
      Algorithm algorithm,
      int limit,
      long remaining,
      long retryAfterMillis,
      Instant resetAt,
      DecisionSource source,
      DecisionReason reason,
      boolean approximate,
      boolean degraded) {
    if (remaining < 0 || remaining > limit) {
      throw new IllegalArgumentException("normal remaining must be between zero and limit");
    }
    RateLimitConstraints.requireInstantWithinLuaRange("resetAt", resetAt);
    if (degraded) {
      throw new IllegalArgumentException("normal Redis/local-lease decisions must not be degraded");
    }
    if (allowed) {
      if (reason != DecisionReason.ALLOWED || retryAfterMillis != 0) {
        throw new IllegalArgumentException("normal allows require ALLOWED and retryAfterMillis=0");
      }
    } else {
      if (source != DecisionSource.REDIS
          || reason != DecisionReason.LIMIT_EXCEEDED
          || retryAfterMillis < 1) {
        throw new IllegalArgumentException(
            "quota denials require REDIS, LIMIT_EXCEEDED, and positive retryAfterMillis");
      }
      RateLimitConstraints.requireLuaEpochMillis("retryAfterMillis", retryAfterMillis);
    }
    if (source == DecisionSource.LOCAL_LEASE && (!allowed || !approximate)) {
      throw new IllegalArgumentException("LOCAL_LEASE must be an approximate allow");
    }
    if (algorithm == Algorithm.SLIDING_WINDOW_COUNTER && !approximate) {
      throw new IllegalArgumentException(
          "sliding-window-counter decisions are intrinsically approximate");
    }
  }
}
