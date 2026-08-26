package dev.ratelimiter.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RateLimitDecisionTest {

  private static final Instant RESET = Instant.ofEpochMilli(1_800_000_000_000L);

  @Test
  void acceptsExactRedisAllowAndDenial() {
    RateLimitDecision allowed =
        decision(
            true,
            Algorithm.TOKEN_BUCKET,
            99,
            1,
            0,
            RESET,
            DecisionSource.REDIS,
            DecisionReason.ALLOWED,
            false,
            false);
    RateLimitDecision denied =
        decision(
            false,
            Algorithm.SLIDING_WINDOW_LOG,
            1,
            0,
            500,
            RESET,
            DecisionSource.REDIS,
            DecisionReason.LIMIT_EXCEEDED,
            false,
            false);

    assertThat(allowed.allowed()).isTrue();
    assertThat(allowed.grantedPermits()).isEqualTo(1);
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.remaining()).isEqualTo(1);
  }

  @Test
  void acceptsApproximateLeasedAndCounterDecisions() {
    assertThat(
            decision(
                    true,
                    Algorithm.TOKEN_BUCKET,
                    98,
                    1,
                    0,
                    RESET,
                    DecisionSource.LOCAL_LEASE,
                    DecisionReason.ALLOWED,
                    true,
                    false)
                .source())
        .isEqualTo(DecisionSource.LOCAL_LEASE);
    assertThat(
            decision(
                    true,
                    Algorithm.SLIDING_WINDOW_COUNTER,
                    59,
                    1,
                    0,
                    RESET,
                    DecisionSource.REDIS,
                    DecisionReason.ALLOWED,
                    true,
                    false)
                .approximate())
        .isTrue();
  }

  @Test
  void acceptsExactSpecifiedFailOpenAndFailClosedShapes() {
    RateLimitDecision failOpen =
        decision(
            true,
            Algorithm.TOKEN_BUCKET,
            -1,
            3,
            -1,
            null,
            DecisionSource.FAIL_OPEN,
            DecisionReason.BACKEND_UNAVAILABLE_FAIL_OPEN,
            true,
            true);
    RateLimitDecision failClosed =
        decision(
            false,
            Algorithm.SLIDING_WINDOW_LOG,
            -1,
            0,
            -1,
            null,
            DecisionSource.FAIL_CLOSED,
            DecisionReason.BACKEND_UNAVAILABLE_FAIL_CLOSED,
            false,
            true);

    assertThat(failOpen.resetAt()).isNull();
    assertThat(failOpen.remaining()).isEqualTo(-1);
    assertThat(failClosed.allowed()).isFalse();
  }

  @Test
  void rejectsInconsistentGrantAndReasonFields() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                decision(
                    false,
                    Algorithm.TOKEN_BUCKET,
                    0,
                    1,
                    10,
                    RESET,
                    DecisionSource.REDIS,
                    DecisionReason.LIMIT_EXCEEDED,
                    false,
                    false))
        .withMessageContaining("zero permits");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                decision(
                    true,
                    Algorithm.TOKEN_BUCKET,
                    9,
                    1,
                    0,
                    RESET,
                    DecisionSource.REDIS,
                    DecisionReason.LIMIT_EXCEEDED,
                    false,
                    false));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                decision(
                    false,
                    Algorithm.TOKEN_BUCKET,
                    0,
                    0,
                    0,
                    RESET,
                    DecisionSource.REDIS,
                    DecisionReason.LIMIT_EXCEEDED,
                    false,
                    false))
        .withMessageContaining("positive retryAfterMillis");
  }

  @Test
  void rejectsUnknownRemainingOrResetForNormalResults() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                decision(
                    true,
                    Algorithm.TOKEN_BUCKET,
                    -1,
                    1,
                    0,
                    RESET,
                    DecisionSource.REDIS,
                    DecisionReason.ALLOWED,
                    false,
                    false));
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                decision(
                    true,
                    Algorithm.TOKEN_BUCKET,
                    1,
                    1,
                    0,
                    null,
                    DecisionSource.REDIS,
                    DecisionReason.ALLOWED,
                    false,
                    false))
        .withMessageContaining("resetAt");
  }

  @Test
  void rejectsMalformedFallbackShapes() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                decision(
                    true,
                    Algorithm.TOKEN_BUCKET,
                    1,
                    1,
                    -1,
                    null,
                    DecisionSource.FAIL_OPEN,
                    DecisionReason.BACKEND_UNAVAILABLE_FAIL_OPEN,
                    true,
                    true));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                decision(
                    false,
                    Algorithm.TOKEN_BUCKET,
                    -1,
                    0,
                    -1,
                    RESET,
                    DecisionSource.FAIL_CLOSED,
                    DecisionReason.BACKEND_UNAVAILABLE_FAIL_CLOSED,
                    false,
                    true));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                decision(
                    true,
                    Algorithm.TOKEN_BUCKET,
                    -1,
                    1,
                    -1,
                    null,
                    DecisionSource.FAIL_OPEN,
                    DecisionReason.BACKEND_UNAVAILABLE_FAIL_OPEN,
                    false,
                    true));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                decision(
                    false,
                    Algorithm.TOKEN_BUCKET,
                    -1,
                    0,
                    -1,
                    null,
                    DecisionSource.FAIL_CLOSED,
                    DecisionReason.BACKEND_UNAVAILABLE_FAIL_CLOSED,
                    false,
                    false));
  }

  @Test
  void requiresApproximationForLocalLeaseAndCounter() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                decision(
                    true,
                    Algorithm.TOKEN_BUCKET,
                    1,
                    1,
                    0,
                    RESET,
                    DecisionSource.LOCAL_LEASE,
                    DecisionReason.ALLOWED,
                    false,
                    false));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                decision(
                    true,
                    Algorithm.SLIDING_WINDOW_COUNTER,
                    1,
                    1,
                    0,
                    RESET,
                    DecisionSource.REDIS,
                    DecisionReason.ALLOWED,
                    false,
                    false));
  }

  @Test
  void rejectsNormalDegradedResultsAndOutOfRangeEpochs() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                decision(
                    true,
                    Algorithm.TOKEN_BUCKET,
                    1,
                    1,
                    0,
                    RESET,
                    DecisionSource.REDIS,
                    DecisionReason.ALLOWED,
                    false,
                    true));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                decision(
                    true,
                    Algorithm.TOKEN_BUCKET,
                    1,
                    1,
                    0,
                    Instant.ofEpochMilli(RateLimitConstraints.LUA_SAFE_INTEGER_EXCLUSIVE),
                    DecisionSource.REDIS,
                    DecisionReason.ALLOWED,
                    false,
                    false));
  }

  private static RateLimitDecision decision(
      boolean allowed,
      Algorithm algorithm,
      long remaining,
      int grantedPermits,
      long retryAfterMillis,
      Instant resetAt,
      DecisionSource source,
      DecisionReason reason,
      boolean approximate,
      boolean degraded) {
    return new RateLimitDecision(
        allowed,
        "api-standard",
        7,
        algorithm,
        100,
        remaining,
        grantedPermits,
        retryAfterMillis,
        resetAt,
        source,
        reason,
        approximate,
        degraded);
  }
}
