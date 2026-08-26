package dev.ratelimiter.starter.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.RedisCommandTimeoutException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;

class BackendDecisionTest {
  @Test
  void decodesTheSharedSevenIntegerContract() {
    BackendDecision allowed =
        BackendDecision.decode(List.of(1L, 4L, 6L, 0L, 500L, 100L, 1_700L), 1, 4);
    BackendDecision denied =
        BackendDecision.decode(List.of(0L, 0L, 0L, 20L, 500L, 0L, 1_700L), 1, 1);

    assertThat(allowed.allowed()).isTrue();
    assertThat(allowed.reservedPermits()).isEqualTo(4);
    assertThat(denied.allowed()).isFalse();
  }

  @Test
  void malformedResultsRemainProgrammingErrors() {
    assertThatThrownBy(() -> BackendDecision.decode(List.of(1L, 1L), 1, 1))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> BackendDecision.decode(List.of(1L, 0L, 0L, 0L, 1L, 1L, 1L), 1, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("reservation contract");
    assertThatThrownBy(() -> BackendDecision.decode(List.of(0L, 0L, 1L, 1L, 1L, 0L, 1L), 1, 1))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void classifierUnwrapsOnlyAvailabilityFailures() {
    RedisAvailabilityClassifier classifier = new RedisAvailabilityClassifier();
    RedisSystemException timeout =
        new RedisSystemException("wrapped", new RedisCommandTimeoutException("timed out"));
    RedisSystemException scriptBug =
        new RedisSystemException("wrapped", new IllegalStateException("Lua bug"));

    assertThat(classifier.classify(timeout))
        .contains(BackendUnavailableException.Category.AMBIGUOUS_EXECUTION);
    assertThat(classifier.classify(scriptBug)).isEmpty();
  }
}
