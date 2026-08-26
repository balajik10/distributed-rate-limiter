package dev.ratelimiter.starter.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ratelimiter.core.Algorithm;
import dev.ratelimiter.core.FailureMode;
import dev.ratelimiter.core.SlidingWindowCounterPolicy;
import dev.ratelimiter.core.TokenBucketPolicy;
import dev.ratelimiter.starter.config.PolicyProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PolicyFactoryValidationBranchesTest {
  private final PolicyFactory factory = new PolicyFactory();

  @Test
  void createsTokenBucketAndCounterPoliciesWithTypedParameters() {
    assertThat(factory.create("token", validToken()))
        .isEqualTo(
            new TokenBucketPolicy(
                "token",
                7,
                100,
                20,
                Duration.ofSeconds(2),
                FailureMode.FAIL_OPEN,
                new dev.ratelimiter.core.LocalCacheSettings(
                    false, 1, Duration.ofMillis(100), 1, 0)));

    PolicyProperties counter = validWindow(Algorithm.SLIDING_WINDOW_COUNTER);
    assertThat(factory.create("counter", counter))
        .isEqualTo(
            new SlidingWindowCounterPolicy(
                "counter",
                7,
                100,
                Duration.ofSeconds(10),
                FailureMode.FAIL_OPEN,
                new dev.ratelimiter.core.LocalCacheSettings(
                    false, 1, Duration.ofMillis(100), 1, 0)));
  }

  @Test
  void rejectsAbsentPolicyAndRequiredTopLevelFieldsWithFieldSpecificMessages() {
    assertThatThrownBy(() -> factory.create("missing", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("policy configuration");

    PolicyProperties missingVersion = validToken();
    missingVersion.setVersion(null);
    assertInvalid(missingVersion, "version is required");

    PolicyProperties missingAlgorithm = validToken();
    missingAlgorithm.setAlgorithm(null);
    assertInvalid(missingAlgorithm, "algorithm is required");

    PolicyProperties missingFailureMode = validToken();
    missingFailureMode.setFailureMode(null);
    assertInvalid(missingFailureMode, "failure-mode is required");
  }

  @Test
  void rejectsEveryMissingAlgorithmParameter() {
    PolicyProperties missingCapacity = validToken();
    missingCapacity.setCapacity(null);
    assertInvalid(missingCapacity, "capacity is required");

    PolicyProperties missingRefillTokens = validToken();
    missingRefillTokens.setRefillTokens(null);
    assertInvalid(missingRefillTokens, "refill-tokens is required");

    PolicyProperties missingRefillPeriod = validToken();
    missingRefillPeriod.setRefillPeriod(null);
    assertInvalid(missingRefillPeriod, "refill-period is required");

    PolicyProperties missingLimit = validWindow(Algorithm.SLIDING_WINDOW_LOG);
    missingLimit.setLimit(null);
    assertInvalid(missingLimit, "limit is required");

    PolicyProperties missingWindow = validWindow(Algorithm.SLIDING_WINDOW_COUNTER);
    missingWindow.setWindow(null);
    assertInvalid(missingWindow, "window is required");
  }

  @Test
  void rejectsContradictoryFieldsAtEveryShortCircuitPosition() {
    PolicyProperties tokenLimit = validToken();
    tokenLimit.setLimit(10);
    assertInvalid(tokenLimit, "window fields");
    PolicyProperties tokenWindow = validToken();
    tokenWindow.setWindow(Duration.ofSeconds(1));
    assertInvalid(tokenWindow, "window fields");

    PolicyProperties logRefillTokens = validWindow(Algorithm.SLIDING_WINDOW_LOG);
    logRefillTokens.setRefillTokens(1);
    assertInvalid(logRefillTokens, "token-bucket fields");
    PolicyProperties logRefillPeriod = validWindow(Algorithm.SLIDING_WINDOW_LOG);
    logRefillPeriod.setRefillPeriod(Duration.ofSeconds(1));
    assertInvalid(logRefillPeriod, "token-bucket fields");

    PolicyProperties counterCapacity = validWindow(Algorithm.SLIDING_WINDOW_COUNTER);
    counterCapacity.setCapacity(1);
    assertInvalid(counterCapacity, "token-bucket fields");
    PolicyProperties counterRefillTokens = validWindow(Algorithm.SLIDING_WINDOW_COUNTER);
    counterRefillTokens.setRefillTokens(1);
    assertInvalid(counterRefillTokens, "token-bucket fields");
    PolicyProperties counterRefillPeriod = validWindow(Algorithm.SLIDING_WINDOW_COUNTER);
    counterRefillPeriod.setRefillPeriod(Duration.ofSeconds(1));
    assertInvalid(counterRefillPeriod, "token-bucket fields");
  }

  @Test
  void rejectsEveryMalformedLocalCacheSetting() {
    PolicyProperties missingCache = validToken();
    missingCache.setLocalCache(null);
    assertInvalid(missingCache, "local-cache is required");

    PolicyProperties leaseTooSmall = validToken();
    leaseTooSmall.getLocalCache().setMaxLeaseSize(0);
    assertInvalid(leaseTooSmall, "invalid max-lease-size");
    PolicyProperties leaseTooLarge = validToken();
    leaseTooLarge.getLocalCache().setMaxLeaseSize(101);
    assertInvalid(leaseTooLarge, "invalid max-lease-size");

    PolicyProperties noInstances = validToken();
    noInstances.getLocalCache().setExpectedMaxInstances(0);
    assertInvalid(noInstances, "invalid expected-max-instances");
    PolicyProperties negativeBudget = validToken();
    negativeBudget.getLocalCache().setMaxErrorPermits(-1);
    assertInvalid(negativeBudget, "invalid max-error-permits");

    PolicyProperties nullTtl = validToken();
    nullTtl.getLocalCache().setMaxLeaseTtl(null);
    assertInvalid(nullTtl, "invalid max-lease-ttl");
    PolicyProperties zeroTtl = validToken();
    zeroTtl.getLocalCache().setMaxLeaseTtl(Duration.ZERO);
    assertInvalid(zeroTtl, "invalid max-lease-ttl");
    PolicyProperties negativeTtl = validToken();
    negativeTtl.getLocalCache().setMaxLeaseTtl(Duration.ofMillis(-1));
    assertInvalid(negativeTtl, "invalid max-lease-ttl");
  }

  @Test
  void enforcesDurationLuaArithmeticAndEmptyRefillBounds() {
    PolicyProperties durationTooShort = validToken();
    durationTooShort.setRefillPeriod(Duration.ofMillis(9));
    assertInvalid(durationTooShort, "between 10ms and 24h");
    PolicyProperties durationTooLong = validToken();
    durationTooLong.setRefillPeriod(Duration.ofHours(24).plusMillis(1));
    assertInvalid(durationTooLong, "between 10ms and 24h");

    PolicyProperties nonpositiveCapacity = validToken();
    nonpositiveCapacity.setCapacity(0);
    assertInvalid(nonpositiveCapacity, "unsafe Lua numeric product");
    PolicyProperties unsafeProduct = validToken();
    unsafeProduct.setCapacity(Integer.MAX_VALUE);
    unsafeProduct.setRefillPeriod(Duration.ofHours(24));
    assertInvalid(unsafeProduct, "unsafe Lua numeric product");

    PolicyProperties excessiveEmptyRefill = validToken();
    excessiveEmptyRefill.setCapacity(31);
    excessiveEmptyRefill.setRefillTokens(1);
    excessiveEmptyRefill.setRefillPeriod(Duration.ofDays(1));
    assertInvalid(excessiveEmptyRefill, "empty-bucket refill exceeds 30 days");
  }

  @Test
  void acceptsLeaseAtBoundsAndRejectsBudgetOrWindowTtlViolations() {
    PolicyProperties leased = validWindow(Algorithm.SLIDING_WINDOW_COUNTER);
    leased.getLocalCache().setEnabled(true);
    leased.getLocalCache().setMaxLeaseSize(2);
    leased.getLocalCache().setExpectedMaxInstances(3);
    leased.getLocalCache().setMaxErrorPermits(3);
    leased.getLocalCache().setMaxLeaseTtl(Duration.ofSeconds(1));
    assertThat(factory.create("leased", leased).localCacheSettings().enabled()).isTrue();

    PolicyProperties overBudget = validToken();
    overBudget.getLocalCache().setEnabled(true);
    overBudget.getLocalCache().setMaxLeaseSize(3);
    overBudget.getLocalCache().setExpectedMaxInstances(2);
    overBudget.getLocalCache().setMaxErrorPermits(3);
    assertInvalid(overBudget, "approximation budget");

    PolicyProperties excessiveWindowTtl = validWindow(Algorithm.SLIDING_WINDOW_COUNTER);
    excessiveWindowTtl.getLocalCache().setEnabled(true);
    excessiveWindowTtl.getLocalCache().setMaxLeaseSize(2);
    excessiveWindowTtl.getLocalCache().setExpectedMaxInstances(1);
    excessiveWindowTtl.getLocalCache().setMaxErrorPermits(1);
    excessiveWindowTtl.getLocalCache().setMaxLeaseTtl(Duration.ofMillis(1_001));
    assertInvalid(excessiveWindowTtl, "window/10");
  }

  private void assertInvalid(PolicyProperties properties, String message) {
    assertThatThrownBy(() -> factory.create("invalid", properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(message);
  }

  private static PolicyProperties validToken() {
    PolicyProperties properties = base(Algorithm.TOKEN_BUCKET);
    properties.setCapacity(100);
    properties.setRefillTokens(20);
    properties.setRefillPeriod(Duration.ofSeconds(2));
    return properties;
  }

  private static PolicyProperties validWindow(Algorithm algorithm) {
    PolicyProperties properties = base(algorithm);
    properties.setLimit(100);
    properties.setWindow(Duration.ofSeconds(10));
    return properties;
  }

  private static PolicyProperties base(Algorithm algorithm) {
    PolicyProperties properties = new PolicyProperties();
    properties.setVersion(7L);
    properties.setAlgorithm(algorithm);
    properties.setFailureMode(FailureMode.FAIL_OPEN);
    properties.getLocalCache().setEnabled(false);
    properties.getLocalCache().setMaxLeaseSize(1);
    properties.getLocalCache().setMaxLeaseTtl(Duration.ofMillis(100));
    properties.getLocalCache().setExpectedMaxInstances(1);
    properties.getLocalCache().setMaxErrorPermits(0);
    return properties;
  }
}
