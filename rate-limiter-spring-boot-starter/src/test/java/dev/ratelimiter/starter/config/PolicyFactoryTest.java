package dev.ratelimiter.starter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ratelimiter.core.Algorithm;
import dev.ratelimiter.core.FailureMode;
import dev.ratelimiter.core.SlidingWindowLogPolicy;
import dev.ratelimiter.starter.policy.PolicyFactory;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PolicyFactoryTest {
  private final PolicyFactory factory = new PolicyFactory();

  @Test
  void createsAValidatedTypedPolicy() {
    PolicyProperties properties = slidingLog();

    assertThat(factory.create("login-strict", properties))
        .isEqualTo(
            new SlidingWindowLogPolicy(
                "login-strict",
                3,
                5,
                Duration.ofSeconds(60),
                FailureMode.FAIL_CLOSED,
                dev.ratelimiter.core.LocalCacheSettings.disabled()));
  }

  @Test
  void rejectsContradictoryAlgorithmFieldsAndUnsafeLeaseBudgets() {
    PolicyProperties contradictory = slidingLog();
    contradictory.setCapacity(10);
    assertThatThrownBy(() -> factory.create("login-strict", contradictory))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("token-bucket fields");

    PolicyProperties leased = slidingLog();
    leased.getLocalCache().setEnabled(true);
    leased.getLocalCache().setMaxLeaseSize(3);
    leased.getLocalCache().setExpectedMaxInstances(10);
    leased.getLocalCache().setMaxErrorPermits(10);
    assertThatThrownBy(() -> factory.create("login-strict", leased))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("approximation budget");
  }

  @Test
  void rejectsLeaseTtlLongerThanOneTenthOfWindow() {
    PolicyProperties leased = slidingLog();
    leased.getLocalCache().setEnabled(true);
    leased.getLocalCache().setMaxLeaseSize(2);
    leased.getLocalCache().setExpectedMaxInstances(1);
    leased.getLocalCache().setMaxErrorPermits(1);
    leased.getLocalCache().setMaxLeaseTtl(Duration.ofSeconds(7));

    assertThatThrownBy(() -> factory.create("login-strict", leased))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("window/10");
  }

  private static PolicyProperties slidingLog() {
    PolicyProperties properties = new PolicyProperties();
    properties.setVersion(3L);
    properties.setAlgorithm(Algorithm.SLIDING_WINDOW_LOG);
    properties.setFailureMode(FailureMode.FAIL_CLOSED);
    properties.setLimit(5);
    properties.setWindow(Duration.ofSeconds(60));
    properties.getLocalCache().setEnabled(false);
    properties.getLocalCache().setMaxLeaseSize(1);
    properties.getLocalCache().setMaxLeaseTtl(Duration.ofMillis(1));
    properties.getLocalCache().setExpectedMaxInstances(1);
    properties.getLocalCache().setMaxErrorPermits(0);
    return properties;
  }
}
