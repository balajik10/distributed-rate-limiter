package dev.ratelimiter.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PublicApiTest {

  @Test
  void convenienceAcquireBuildsAUnitPermitRequest() {
    AtomicReference<RateLimitRequest> captured = new AtomicReference<>();
    DistributedRateLimiter limiter =
        request -> {
          captured.set(request);
          return allowed(request.policyId(), request.permits());
        };

    RateLimitDecision decision = limiter.tryAcquire("api-standard", "user:123");

    assertThat(captured.get()).isEqualTo(new RateLimitRequest("api-standard", "user:123", 1));
    assertThat(decision.grantedPermits()).isEqualTo(1);
  }

  @Test
  void policyProviderResolvesAndReportsUnknownPolicies() {
    RateLimitPolicy configured =
        new TokenBucketPolicy(
            "api-standard",
            2,
            100,
            100,
            Duration.ofMinutes(1),
            FailureMode.FAIL_OPEN,
            LocalCacheSettings.disabled());
    PolicyProvider provider =
        policyId -> "api-standard".equals(policyId) ? Optional.of(configured) : Optional.empty();

    assertThat(provider.requireById("api-standard")).isSameAs(configured);
    assertThat(provider.policies()).isEmpty();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> provider.requireById("missing"))
        .isInstanceOfSatisfying(
            UnknownPolicyException.class,
            exception -> assertThat(exception.policyId()).isEqualTo("missing"));
  }

  @Test
  void policyProviderRejectsBrokenNullOptionalImplementations() {
    PolicyProvider broken = ignored -> null;

    assertThatNullPointerException()
        .isThrownBy(() -> broken.requireById("api-standard"))
        .withMessageContaining("must not return null");
  }

  @Test
  void permitLimitExceptionCarriesSafeStructuredFields() {
    RateLimitPolicy configured =
        new SlidingWindowLogPolicy(
            "login-strict",
            4,
            5,
            Duration.ofMinutes(1),
            FailureMode.FAIL_CLOSED,
            LocalCacheSettings.disabled());

    assertThatIllegalArgumentException()
        .isThrownBy(() -> configured.validatePermits(6))
        .isInstanceOfSatisfying(
            PermitsExceedPolicyLimitException.class,
            exception -> {
              assertThat(exception.policyId()).isEqualTo("login-strict");
              assertThat(exception.requestedPermits()).isEqualTo(6);
              assertThat(exception.policyLimit()).isEqualTo(5);
            });
  }

  private static RateLimitDecision allowed(String policyId, int permits) {
    return new RateLimitDecision(
        true,
        policyId,
        1,
        Algorithm.TOKEN_BUCKET,
        100,
        100 - permits,
        permits,
        0,
        Instant.ofEpochMilli(1_800_000_000_000L),
        DecisionSource.REDIS,
        DecisionReason.ALLOWED,
        false,
        false);
  }
}
