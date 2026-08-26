package dev.ratelimiter.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PolicyValidationTest {

  private static final LocalCacheSettings DISABLED = LocalCacheSettings.disabled();

  @Test
  void constructsTheThreeSeedPolicyShapes() {
    RateLimitPolicy token =
        new TokenBucketPolicy(
            "api-standard",
            1,
            100,
            100,
            Duration.ofSeconds(60),
            FailureMode.FAIL_OPEN,
            new LocalCacheSettings(true, 10, Duration.ofMillis(100), 10, 90));
    RateLimitPolicy log =
        new SlidingWindowLogPolicy(
            "login-strict",
            1,
            5,
            Duration.ofSeconds(60),
            FailureMode.FAIL_CLOSED,
            new LocalCacheSettings(false, 1, Duration.ofMillis(100), 10, 0));
    RateLimitPolicy counter =
        new SlidingWindowCounterPolicy(
            "search-default",
            1,
            60,
            Duration.ofSeconds(60),
            FailureMode.FAIL_OPEN,
            new LocalCacheSettings(true, 5, Duration.ofMillis(100), 10, 40));

    assertThat(token.algorithm()).isEqualTo(Algorithm.TOKEN_BUCKET);
    assertThat(token.limit()).isEqualTo(100);
    assertThat(token.policyId()).isEqualTo("api-standard");
    assertThat(token.policyVersion()).isEqualTo(1);
    assertThat(log.algorithm()).isEqualTo(Algorithm.SLIDING_WINDOW_LOG);
    assertThat(counter.algorithm()).isEqualTo(Algorithm.SLIDING_WINDOW_COUNTER);
  }

  @Test
  void policyHierarchyIsClosedToUnapprovedImplementations() {
    assertThat(RateLimitPolicy.class.isSealed()).isTrue();
    assertThat(RateLimitPolicy.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(
            TokenBucketPolicy.class,
            SlidingWindowLogPolicy.class,
            SlidingWindowCounterPolicy.class);
  }

  @Test
  void rejectsInvalidCommonPolicyFields() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TokenBucketPolicy(
                    "Bad", 1, 1, 1, Duration.ofSeconds(1), FailureMode.FAIL_OPEN, DISABLED));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TokenBucketPolicy(
                    "p", 0, 1, 1, Duration.ofSeconds(1), FailureMode.FAIL_OPEN, DISABLED));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TokenBucketPolicy(
                    "p",
                    RateLimitConstraints.LUA_SAFE_INTEGER_EXCLUSIVE,
                    1,
                    1,
                    Duration.ofSeconds(1),
                    FailureMode.FAIL_OPEN,
                    DISABLED));
    assertThatNullPointerException()
        .isThrownBy(
            () -> new TokenBucketPolicy("p", 1, 1, 1, Duration.ofSeconds(1), null, DISABLED));
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new TokenBucketPolicy(
                    "p", 1, 1, 1, Duration.ofSeconds(1), FailureMode.FAIL_OPEN, null));
  }

  @Test
  void validatesTokenBucketBoundsAndThirtyDayRefillLimit() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TokenBucketPolicy(
                    "p", 1, 0, 1, Duration.ofSeconds(1), FailureMode.FAIL_OPEN, DISABLED))
        .withMessageContaining("capacity");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TokenBucketPolicy(
                    "p", 1, 1_000_001, 1, Duration.ofSeconds(1), FailureMode.FAIL_OPEN, DISABLED));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TokenBucketPolicy(
                    "p", 1, 1, 0, Duration.ofSeconds(1), FailureMode.FAIL_OPEN, DISABLED))
        .withMessageContaining("refillTokens");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TokenBucketPolicy(
                    "p", 1, 1_000_000, 1, Duration.ofHours(24), FailureMode.FAIL_OPEN, DISABLED))
        .withMessageContaining("30 days");
  }

  @Test
  void validatesAlgorithmDurationRangeAndPrecision() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TokenBucketPolicy(
                    "p", 1, 1, 1, Duration.ofMillis(9), FailureMode.FAIL_OPEN, DISABLED));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new SlidingWindowCounterPolicy(
                    "p",
                    1,
                    1,
                    Duration.ofHours(24).plusMillis(1),
                    FailureMode.FAIL_OPEN,
                    DISABLED));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new SlidingWindowLogPolicy(
                    "p", 1, 1, Duration.ofNanos(10_000_001), FailureMode.FAIL_OPEN, DISABLED))
        .withMessageContaining("whole-millisecond");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TokenBucketPolicy(
                    "p",
                    1,
                    1,
                    1,
                    Duration.ofSeconds(Long.MAX_VALUE),
                    FailureMode.FAIL_OPEN,
                    DISABLED))
        .withMessageContaining("24h");
  }

  @Test
  void enforcesAlgorithmSpecificLimitBounds() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new SlidingWindowLogPolicy(
                    "p", 1, 100_001, Duration.ofSeconds(1), FailureMode.FAIL_OPEN, DISABLED));
    assertThat(
            new SlidingWindowCounterPolicy(
                    "p", 1, 1_000_000, Duration.ofSeconds(1), FailureMode.FAIL_OPEN, DISABLED)
                .limit())
        .isEqualTo(1_000_000);
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new SlidingWindowCounterPolicy(
                    "p", 1, 1_000_001, Duration.ofSeconds(1), FailureMode.FAIL_OPEN, DISABLED));
  }

  @Test
  void validatesLeaseBoundsAndContradictoryDisabledSettings() {
    assertThat(LocalCacheSettings.disabled().plannedTimingErrorPermits()).isZero();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LocalCacheSettings(true, 0, Duration.ofMillis(1), 1, 0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LocalCacheSettings(true, 101, Duration.ofMillis(1), 1, 100));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LocalCacheSettings(true, 1, Duration.ZERO, 1, 0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LocalCacheSettings(true, 1, Duration.ofNanos(1_000_001), 1, 0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LocalCacheSettings(true, 1, Duration.ofHours(25), 1, 0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LocalCacheSettings(true, 1, Duration.ofMillis(1), 0, 0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LocalCacheSettings(true, 1, Duration.ofMillis(1), 1, -1));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LocalCacheSettings(false, 2, Duration.ofMillis(1), 1, 0))
        .withMessageContaining("maxLeaseSize=1");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LocalCacheSettings(false, 1, Duration.ofMillis(1), 1, 1))
        .withMessageContaining("maxErrorPermits=0");
  }

  @Test
  void enforcesQuantifiedLeaseErrorBudget() {
    LocalCacheSettings exactBudget =
        new LocalCacheSettings(true, 10, Duration.ofMillis(100), 10, 90);

    assertThat(exactBudget.plannedTimingErrorPermits()).isEqualTo(90);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LocalCacheSettings(true, 10, Duration.ofMillis(100), 10, 89))
        .withMessageContaining("maxErrorPermits");
  }

  @Test
  void enforcesLeaseSizeAndWindowTtlAgainstSelectedPolicy() {
    LocalCacheSettings tooLargeForLimit =
        new LocalCacheSettings(true, 6, Duration.ofMillis(1), 1, 5);
    LocalCacheSettings ttlAboveTenth =
        new LocalCacheSettings(true, 2, Duration.ofMillis(101), 1, 1);
    LocalCacheSettings ttlAtTenth = new LocalCacheSettings(true, 2, Duration.ofMillis(100), 1, 1);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new SlidingWindowLogPolicy(
                    "p", 1, 5, Duration.ofSeconds(1), FailureMode.FAIL_OPEN, tooLargeForLimit));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new SlidingWindowCounterPolicy(
                    "p", 1, 10, Duration.ofSeconds(1), FailureMode.FAIL_OPEN, ttlAboveTenth))
        .withMessageContaining("one tenth");
    assertThat(
            new SlidingWindowCounterPolicy(
                    "p", 1, 10, Duration.ofSeconds(1), FailureMode.FAIL_OPEN, ttlAtTenth)
                .localCacheSettings())
        .isSameAs(ttlAtTenth);
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TokenBucketPolicy(
                    "p",
                    1,
                    1,
                    1,
                    Duration.ofSeconds(1),
                    FailureMode.FAIL_OPEN,
                    new LocalCacheSettings(true, 2, Duration.ofMillis(1), 1, 1)))
        .withMessageContaining("capacity");
  }

  @Test
  void maximumConfigurationsKeepLuaProductsExact() {
    LocalCacheSettings tokenCache =
        new LocalCacheSettings(
            true, 100, Duration.ofHours(24), Integer.MAX_VALUE, (long) Integer.MAX_VALUE * 99);
    TokenBucketPolicy token =
        new TokenBucketPolicy(
            "token-max",
            RateLimitConstraints.LUA_SAFE_INTEGER_EXCLUSIVE - 1,
            1_000_000,
            1_000_000,
            Duration.ofHours(24),
            FailureMode.FAIL_OPEN,
            tokenCache);
    SlidingWindowCounterPolicy counter =
        new SlidingWindowCounterPolicy(
            "counter-max", 1, 1_000_000, Duration.ofHours(24), FailureMode.FAIL_OPEN, DISABLED);

    assertThat((long) token.capacity() * token.refillPeriod().toMillis())
        .isLessThan(RateLimitConstraints.LUA_SAFE_INTEGER_EXCLUSIVE);
    assertThat((long) counter.limit() * counter.window().toMillis())
        .isLessThan(RateLimitConstraints.LUA_SAFE_INTEGER_EXCLUSIVE);
    assertThat(tokenCache.plannedTimingErrorPermits())
        .isLessThan(RateLimitConstraints.LUA_SAFE_INTEGER_EXCLUSIVE);
  }

  @Test
  void tokenResetArithmeticRejectsTheLuaBoundaryBeforeMutation() {
    RateLimitConstraints.validateTokenResetArithmetic(1_700_000_000_000L, 2_592_000_000L);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                RateLimitConstraints.validateTokenResetArithmetic(
                    RateLimitConstraints.LUA_SAFE_INTEGER_EXCLUSIVE - 1_000L, 0))
        .withMessageContaining("below 2^53 - 1");
  }

  @Test
  void validatesResolvedRequestAgainstPolicyLimit() {
    RateLimitPolicy policy =
        new SlidingWindowLogPolicy(
            "login", 3, 5, Duration.ofMinutes(1), FailureMode.FAIL_CLOSED, DISABLED);

    policy.validatePermits(5);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> policy.validatePermits(6))
        .isInstanceOf(PermitsExceedPolicyLimitException.class);
  }
}
