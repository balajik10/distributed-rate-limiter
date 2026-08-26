package dev.ratelimiter.starter;

import dev.ratelimiter.core.Algorithm;
import dev.ratelimiter.core.DecisionReason;
import dev.ratelimiter.core.DecisionSource;
import dev.ratelimiter.core.DistributedRateLimiter;
import dev.ratelimiter.core.FailureMode;
import dev.ratelimiter.core.PolicyProvider;
import dev.ratelimiter.core.RateLimitDecision;
import dev.ratelimiter.core.RateLimitPolicy;
import dev.ratelimiter.core.RateLimitRequest;
import dev.ratelimiter.starter.backend.BackendDecision;
import dev.ratelimiter.starter.backend.BackendUnavailableException;
import dev.ratelimiter.starter.backend.RedisRateLimitBackend;
import dev.ratelimiter.starter.cache.LeaseAcquisition;
import dev.ratelimiter.starter.cache.LocalLeaseManager;
import dev.ratelimiter.starter.hash.KeyHasher;
import dev.ratelimiter.starter.metrics.RateLimiterMetrics;
import java.time.Instant;

public final class DefaultDistributedRateLimiter implements DistributedRateLimiter {
  private final PolicyProvider policies;
  private final KeyHasher keyHasher;
  private final RedisRateLimitBackend backend;
  private final LocalLeaseManager leases;
  private final RateLimiterMetrics metrics;

  public DefaultDistributedRateLimiter(
      PolicyProvider policies,
      KeyHasher keyHasher,
      RedisRateLimitBackend backend,
      LocalLeaseManager leases,
      RateLimiterMetrics metrics) {
    this.policies = policies;
    this.keyHasher = keyHasher;
    this.backend = backend;
    this.leases = leases;
    this.metrics = metrics;
  }

  @Override
  public RateLimitDecision tryAcquire(RateLimitRequest request) {
    long started = metrics.start();
    RateLimitPolicy policy = policies.requireById(request.policyId());
    policy.validatePermits(request.permits());
    String digest = keyHasher.digest(policy.id(), policy.version(), request.key());

    RateLimitDecision decision;
    try {
      if (request.permits() == 1
          && policy.localCacheSettings().enabled()
          && policy.localCacheSettings().maxLeaseSize() > 1) {
        decision = fromLease(policy, leases.acquireUnit(policy, digest));
      } else {
        decision =
            fromBackend(
                policy,
                request.permits(),
                backend.reserve(policy, digest, request.permits(), request.permits()));
      }
    } catch (BackendUnavailableException unavailable) {
      decision = fallback(policy, request.permits());
    }
    metrics.decision(policy, decision, started);
    return decision;
  }

  private RateLimitDecision fromLease(RateLimitPolicy policy, LeaseAcquisition acquisition) {
    boolean allowed = acquisition.allowed();
    boolean approximate =
        policy.algorithm() == Algorithm.SLIDING_WINDOW_COUNTER
            || acquisition.cachedTail()
            || acquisition.source() == DecisionSource.LOCAL_LEASE;
    return new RateLimitDecision(
        allowed,
        policy.id(),
        policy.version(),
        policy.algorithm(),
        policy.limit(),
        acquisition.remaining(),
        allowed ? 1 : 0,
        acquisition.retryAfterMillis(),
        Instant.ofEpochMilli(acquisition.resetAtEpochMillis()),
        acquisition.source(),
        allowed ? DecisionReason.ALLOWED : DecisionReason.LIMIT_EXCEEDED,
        approximate,
        false);
  }

  private RateLimitDecision fromBackend(
      RateLimitPolicy policy, int requestedPermits, BackendDecision backendDecision) {
    boolean allowed = backendDecision.allowed();
    long resetAt =
        safeAdd(backendDecision.effectiveNowEpochMillis(), backendDecision.resetAfterMillis());
    return new RateLimitDecision(
        allowed,
        policy.id(),
        policy.version(),
        policy.algorithm(),
        policy.limit(),
        backendDecision.centralRemaining(),
        allowed ? requestedPermits : 0,
        backendDecision.retryAfterMillis(),
        Instant.ofEpochMilli(resetAt),
        DecisionSource.REDIS,
        allowed ? DecisionReason.ALLOWED : DecisionReason.LIMIT_EXCEEDED,
        policy.algorithm() == Algorithm.SLIDING_WINDOW_COUNTER,
        false);
  }

  private RateLimitDecision fallback(RateLimitPolicy policy, int requestedPermits) {
    if (policy.failureMode() == FailureMode.FAIL_OPEN) {
      metrics.fallback(policy, DecisionSource.FAIL_OPEN);
      return new RateLimitDecision(
          true,
          policy.id(),
          policy.version(),
          policy.algorithm(),
          policy.limit(),
          -1,
          requestedPermits,
          -1,
          null,
          DecisionSource.FAIL_OPEN,
          DecisionReason.BACKEND_UNAVAILABLE_FAIL_OPEN,
          true,
          true);
    }
    metrics.fallback(policy, DecisionSource.FAIL_CLOSED);
    return new RateLimitDecision(
        false,
        policy.id(),
        policy.version(),
        policy.algorithm(),
        policy.limit(),
        -1,
        0,
        -1,
        null,
        DecisionSource.FAIL_CLOSED,
        DecisionReason.BACKEND_UNAVAILABLE_FAIL_CLOSED,
        false,
        true);
  }

  private static long safeAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException("Rate-limit reset timestamp overflow", overflow);
    }
  }
}
