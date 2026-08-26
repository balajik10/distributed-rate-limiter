package dev.ratelimiter.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ratelimiter.core.DecisionReason;
import dev.ratelimiter.core.DecisionSource;
import dev.ratelimiter.core.FailureMode;
import dev.ratelimiter.core.LocalCacheSettings;
import dev.ratelimiter.core.PolicyProvider;
import dev.ratelimiter.core.RateLimitRequest;
import dev.ratelimiter.core.TokenBucketPolicy;
import dev.ratelimiter.starter.backend.BackendDecision;
import dev.ratelimiter.starter.backend.BackendUnavailableException;
import dev.ratelimiter.starter.backend.RedisAvailabilityClassifier;
import dev.ratelimiter.starter.backend.RedisRateLimitBackend;
import dev.ratelimiter.starter.hash.KeyHasher;
import dev.ratelimiter.starter.metrics.RateLimiterMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultDistributedRateLimiterTest {
  @Test
  void mapsAHealthyBackendDecisionWithoutExposingTheReservationBatch() {
    TokenBucketPolicy policy = policy(FailureMode.FAIL_OPEN);
    RedisRateLimitBackend backend =
        stub(
            (ignoredPolicy, digest, minimum, desired) ->
                new BackendDecision(true, 1, 7, 0, 500, 100, 1_700_000_000_000L));
    DefaultDistributedRateLimiter limiter = limiter(policy, backend);

    var decision = limiter.tryAcquire(new RateLimitRequest(policy.id(), "private-key", 1));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.grantedPermits()).isOne();
    assertThat(decision.source()).isEqualTo(DecisionSource.REDIS);
    assertThat(decision.remaining()).isEqualTo(7);
  }

  @Test
  void appliesTheSelectedFailureModeOnlyForAvailabilityFailures() {
    RedisRateLimitBackend backend =
        stub(
            (ignoredPolicy, digest, minimum, desired) -> {
              throw new BackendUnavailableException(
                  BackendUnavailableException.Category.CONNECTION,
                  new IllegalStateException("connection unavailable"));
            });

    var open =
        limiter(policy(FailureMode.FAIL_OPEN), backend)
            .tryAcquire(new RateLimitRequest("test-policy", "subject", 2));
    var closed =
        limiter(policy(FailureMode.FAIL_CLOSED), backend)
            .tryAcquire(new RateLimitRequest("test-policy", "subject", 2));

    assertThat(open.source()).isEqualTo(DecisionSource.FAIL_OPEN);
    assertThat(open.reason()).isEqualTo(DecisionReason.BACKEND_UNAVAILABLE_FAIL_OPEN);
    assertThat(open.remaining()).isEqualTo(-1);
    assertThat(open.resetAt()).isNull();
    assertThat(closed.source()).isEqualTo(DecisionSource.FAIL_CLOSED);
    assertThat(closed.reason()).isEqualTo(DecisionReason.BACKEND_UNAVAILABLE_FAIL_CLOSED);
    assertThat(closed.allowed()).isFalse();
  }

  @Test
  void neverTurnsProgrammingErrorsIntoFailOpenAllows() {
    RedisRateLimitBackend backend =
        stub(
            (ignoredPolicy, digest, minimum, desired) -> {
              throw new IllegalStateException("malformed Lua result");
            });

    assertThatThrownBy(
            () ->
                limiter(policy(FailureMode.FAIL_OPEN), backend)
                    .tryAcquire(new RateLimitRequest("test-policy", "subject", 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("malformed Lua result");
  }

  @Test
  void weightedRequestsBypassLeasingAndUseStrictReservationBounds() {
    TokenBucketPolicy leasedPolicy =
        new TokenBucketPolicy(
            "test-policy",
            1,
            10,
            10,
            Duration.ofSeconds(1),
            FailureMode.FAIL_OPEN,
            new LocalCacheSettings(true, 10, Duration.ofMillis(100), 1, 9));
    RedisRateLimitBackend backend =
        stub(
            (policy, digest, minimum, desired) -> {
              assertThat(minimum).isEqualTo(2);
              assertThat(desired).isEqualTo(2);
              return new BackendDecision(true, 2, 8, 0, 200, 100, 1_700_000_000_000L);
            });

    var decision =
        limiter(leasedPolicy, backend)
            .tryAcquire(new RateLimitRequest("test-policy", "subject", 2));

    assertThat(decision.grantedPermits()).isEqualTo(2);
    assertThat(decision.source()).isEqualTo(DecisionSource.REDIS);
  }

  private static DefaultDistributedRateLimiter limiter(
      TokenBucketPolicy policy, RedisRateLimitBackend backend) {
    PolicyProvider provider = id -> Optional.ofNullable(id.equals(policy.id()) ? policy : null);
    RateLimiterMetrics metrics = new RateLimiterMetrics(new SimpleMeterRegistry());
    return new DefaultDistributedRateLimiter(provider, new KeyHasher(null), backend, null, metrics);
  }

  private static RedisRateLimitBackend stub(Reservation reservation) {
    RateLimiterMetrics metrics = new RateLimiterMetrics(new SimpleMeterRegistry());
    return new RedisRateLimitBackend(null, null, null, new RedisAvailabilityClassifier(), metrics) {
      @Override
      public BackendDecision reserve(
          dev.ratelimiter.core.RateLimitPolicy policy,
          String subjectDigest,
          int minimumPermits,
          int desiredPermits) {
        return reservation.reserve(policy, subjectDigest, minimumPermits, desiredPermits);
      }
    };
  }

  @FunctionalInterface
  private interface Reservation {
    BackendDecision reserve(
        dev.ratelimiter.core.RateLimitPolicy policy,
        String subjectDigest,
        int minimumPermits,
        int desiredPermits);
  }

  private static TokenBucketPolicy policy(FailureMode failureMode) {
    return new TokenBucketPolicy(
        "test-policy",
        1,
        10,
        10,
        Duration.ofSeconds(1),
        failureMode,
        LocalCacheSettings.disabled());
  }
}
