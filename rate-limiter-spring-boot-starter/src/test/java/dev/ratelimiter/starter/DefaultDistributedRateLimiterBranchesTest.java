package dev.ratelimiter.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.ratelimiter.core.DecisionReason;
import dev.ratelimiter.core.DecisionSource;
import dev.ratelimiter.core.FailureMode;
import dev.ratelimiter.core.LocalCacheSettings;
import dev.ratelimiter.core.PolicyProvider;
import dev.ratelimiter.core.RateLimitPolicy;
import dev.ratelimiter.core.RateLimitRequest;
import dev.ratelimiter.core.SlidingWindowCounterPolicy;
import dev.ratelimiter.core.TokenBucketPolicy;
import dev.ratelimiter.starter.backend.BackendDecision;
import dev.ratelimiter.starter.backend.RedisAvailabilityClassifier;
import dev.ratelimiter.starter.backend.RedisRateLimitBackend;
import dev.ratelimiter.starter.cache.LocalLeaseManager;
import dev.ratelimiter.starter.config.RateLimiterProperties;
import dev.ratelimiter.starter.hash.KeyHasher;
import dev.ratelimiter.starter.metrics.RateLimiterMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultDistributedRateLimiterBranchesTest {
  @Test
  void leaseMappingDistinguishesExactRedisCachedTailAndLocalSources() {
    TokenBucketPolicy policy = leasedToken(3);
    AtomicInteger redisCalls = new AtomicInteger();
    RedisRateLimitBackend backend =
        backend(
            (ignoredPolicy, digest, minimum, desired) -> {
              redisCalls.incrementAndGet();
              return new BackendDecision(true, desired, 7, 0, 500, 100, 1_000);
            });
    LocalLeaseManager leases = manager(backend);
    DefaultDistributedRateLimiter limiter = limiter(policy, backend, leases);
    RateLimitRequest request = new RateLimitRequest(policy.id(), "customer-42", 1);

    var exactRedis = limiter.tryAcquire(request);
    var cachedTail = limiter.tryAcquire(request);
    var local = limiter.tryAcquire(request);

    assertThat(exactRedis.approximate()).isFalse();
    assertThat(exactRedis.source()).isEqualTo(DecisionSource.REDIS);
    assertThat(exactRedis.resetAt()).isEqualTo(Instant.ofEpochMilli(1_500));
    assertThat(cachedTail.approximate()).isTrue();
    assertThat(cachedTail.remaining()).isEqualTo(8);
    assertThat(local.approximate()).isTrue();
    assertThat(local.source()).isEqualTo(DecisionSource.LOCAL_LEASE);
    assertThat(local.retryAfterMillis()).isZero();
    assertThat(redisCalls).hasValue(2);
  }

  @Test
  void deniedCounterLeaseMapsRetryAndApproximationWithoutGrantingAPermit() {
    SlidingWindowCounterPolicy policy = leasedCounter();
    RedisRateLimitBackend backend =
        backend(
            (ignoredPolicy, digest, minimum, desired) ->
                new BackendDecision(false, 0, 0, 75, 500, 0, 1_000));

    var decision =
        limiter(policy, backend, manager(backend))
            .tryAcquire(new RateLimitRequest(policy.id(), "customer-42", 1));

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.grantedPermits()).isZero();
    assertThat(decision.reason()).isEqualTo(DecisionReason.LIMIT_EXCEEDED);
    assertThat(decision.retryAfterMillis()).isEqualTo(75);
    assertThat(decision.approximate()).isTrue();
  }

  @Test
  void enabledCacheWithBatchOneDeliberatelyUsesStrictBackendPath() {
    TokenBucketPolicy policy = leasedToken(1);
    AtomicInteger redisCalls = new AtomicInteger();
    RedisRateLimitBackend backend =
        backend(
            (ignoredPolicy, digest, minimum, desired) -> {
              redisCalls.incrementAndGet();
              return new BackendDecision(true, 1, 9, 0, 100, 50, 1_000);
            });

    var decision =
        limiter(policy, backend, manager(backend))
            .tryAcquire(new RateLimitRequest(policy.id(), "customer-42", 1));

    assertThat(decision.source()).isEqualTo(DecisionSource.REDIS);
    assertThat(decision.approximate()).isFalse();
    assertThat(redisCalls).hasValue(1);
  }

  @Test
  void strictCounterDenialMapsBackendTimeAndCounterApproximation() {
    SlidingWindowCounterPolicy policy =
        new SlidingWindowCounterPolicy(
            "strict-counter",
            2,
            10,
            Duration.ofSeconds(10),
            FailureMode.FAIL_CLOSED,
            LocalCacheSettings.disabled());
    RedisRateLimitBackend backend =
        backend(
            (ignoredPolicy, digest, minimum, desired) ->
                new BackendDecision(false, 0, 0, 75, 500, 0, 1_000));

    var decision =
        limiter(policy, backend, manager(backend))
            .tryAcquire(new RateLimitRequest(policy.id(), "customer-42", 1));

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.grantedPermits()).isZero();
    assertThat(decision.reason()).isEqualTo(DecisionReason.LIMIT_EXCEEDED);
    assertThat(decision.resetAt()).isEqualTo(Instant.ofEpochMilli(1_500));
    assertThat(decision.approximate()).isTrue();
  }

  @Test
  void overflowingBackendResetTimestampIsRejectedInsteadOfWrapping() {
    TokenBucketPolicy policy = leasedToken(1);
    RedisRateLimitBackend backend =
        backend(
            (ignoredPolicy, digest, minimum, desired) ->
                new BackendDecision(true, 1, 0, 0, 1, 1, Long.MAX_VALUE));

    assertThatThrownBy(
            () ->
                limiter(policy, backend, manager(backend))
                    .tryAcquire(new RateLimitRequest(policy.id(), "customer-42", 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("timestamp overflow")
        .hasCauseInstanceOf(ArithmeticException.class);
  }

  private static DefaultDistributedRateLimiter limiter(
      RateLimitPolicy policy, RedisRateLimitBackend backend, LocalLeaseManager leases) {
    PolicyProvider provider = id -> Optional.ofNullable(policy.id().equals(id) ? policy : null);
    return new DefaultDistributedRateLimiter(
        provider,
        new KeyHasher(null),
        backend,
        leases,
        new RateLimiterMetrics(new SimpleMeterRegistry()));
  }

  private static LocalLeaseManager manager(RedisRateLimitBackend backend) {
    RateLimiterProperties properties = new RateLimiterProperties();
    properties.setCacheMaximumSize(16);
    properties.setLockStripes(4);
    properties.setRampUpDuration(Duration.ofSeconds(2));
    return new LocalLeaseManager(
        backend, new RateLimiterMetrics(new SimpleMeterRegistry()), properties, (Ticker) () -> 0L);
  }

  private static RedisRateLimitBackend backend(Reservation reservation) {
    return new RedisRateLimitBackend(
        null,
        null,
        null,
        new RedisAvailabilityClassifier(),
        new RateLimiterMetrics(new SimpleMeterRegistry())) {
      @Override
      public BackendDecision reserve(
          RateLimitPolicy policy, String subjectDigest, int minimumPermits, int desiredPermits) {
        return reservation.reserve(policy, subjectDigest, minimumPermits, desiredPermits);
      }
    };
  }

  private static TokenBucketPolicy leasedToken(int maximumLeaseSize) {
    return new TokenBucketPolicy(
        "leased-token",
        2,
        10,
        10,
        Duration.ofSeconds(1),
        FailureMode.FAIL_CLOSED,
        new LocalCacheSettings(
            true, maximumLeaseSize, Duration.ofMillis(100), 1, maximumLeaseSize - 1L));
  }

  private static SlidingWindowCounterPolicy leasedCounter() {
    return new SlidingWindowCounterPolicy(
        "leased-counter",
        2,
        10,
        Duration.ofSeconds(10),
        FailureMode.FAIL_CLOSED,
        new LocalCacheSettings(true, 2, Duration.ofMillis(100), 1, 1));
  }

  @FunctionalInterface
  private interface Reservation {
    BackendDecision reserve(
        RateLimitPolicy policy, String digest, int minimumPermits, int desiredPermits);
  }
}
