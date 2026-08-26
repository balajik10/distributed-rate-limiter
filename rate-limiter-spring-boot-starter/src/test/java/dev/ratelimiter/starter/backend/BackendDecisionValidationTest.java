package dev.ratelimiter.starter.backend;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BackendDecisionValidationTest {
  @ParameterizedTest(name = "rejects negative field {0}")
  @MethodSource("negativeDecisionFields")
  void constructorRejectsEveryNegativeBackendValue(
      String field,
      int reserved,
      long remaining,
      long retry,
      long reset,
      long validFor,
      long effectiveNow) {
    assertThatThrownBy(
            () ->
                new BackendDecision(
                    true, reserved, remaining, retry, reset, validFor, effectiveNow))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("negative value");
  }

  @Test
  void decodeRejectsInvalidContainerFlagAndReservedRange() {
    assertThatThrownBy(() -> BackendDecision.decode("not-a-list", 1, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("seven-integer");
    assertMalformed(List.of(2L, 0L, 0L, 1L, 1L, 0L, 1L), "allowed flag");
    assertMalformed(
        List.of(1L, (long) Integer.MAX_VALUE + 1, 0L, 0L, 1L, 1L, 1L), "Java integer range");
  }

  @ParameterizedTest(name = "rejects malformed allowed contract: {0}")
  @MethodSource("invalidAllowedResults")
  void decodeEnforcesEveryAllowedReservationInvariant(String violation, List<?> raw) {
    assertMalformed(raw, "allowed result violates");
  }

  @ParameterizedTest(name = "rejects malformed denied contract: {0}")
  @MethodSource("invalidDeniedResults")
  void decodeEnforcesEveryDeniedReservationInvariant(String violation, List<?> raw) {
    assertMalformed(raw, "denied result violates");
  }

  @ParameterizedTest(name = "rejects malformed numeric value: {0}")
  @MethodSource("invalidNumericValues")
  void decodeRequiresFiniteNonnegativeIntegralNumbers(String violation, Object value) {
    assertMalformed(List.of(1L, value, 0L, 0L, 1L, 1L, 1L), "element 1");
  }

  private static void assertMalformed(List<?> raw, String message) {
    assertThatThrownBy(() -> BackendDecision.decode(raw, 1, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(message);
  }

  private static Stream<Arguments> negativeDecisionFields() {
    return Stream.of(
        Arguments.of("reservedPermits", -1, 0L, 0L, 0L, 0L, 0L),
        Arguments.of("centralRemaining", 0, -1L, 0L, 0L, 0L, 0L),
        Arguments.of("retryAfterMillis", 0, 0L, -1L, 0L, 0L, 0L),
        Arguments.of("resetAfterMillis", 0, 0L, 0L, -1L, 0L, 0L),
        Arguments.of("reservationValidForMillis", 0, 0L, 0L, 0L, -1L, 0L),
        Arguments.of("effectiveNowEpochMillis", 0, 0L, 0L, 0L, 0L, -1L));
  }

  private static Stream<Arguments> invalidAllowedResults() {
    return Stream.of(
        Arguments.of("reserved below minimum", List.of(1L, 0L, 0L, 0L, 1L, 1L, 1L)),
        Arguments.of("reserved above desired", List.of(1L, 2L, 0L, 0L, 1L, 1L, 1L)),
        Arguments.of("nonzero retry", List.of(1L, 1L, 0L, 1L, 1L, 1L, 1L)),
        Arguments.of("zero validity", List.of(1L, 1L, 0L, 0L, 1L, 0L, 1L)));
  }

  private static Stream<Arguments> invalidDeniedResults() {
    return Stream.of(
        Arguments.of("reserved permits", List.of(0L, 1L, 0L, 1L, 1L, 0L, 1L)),
        Arguments.of("enough remaining", List.of(0L, 0L, 1L, 1L, 1L, 0L, 1L)),
        Arguments.of("zero retry", List.of(0L, 0L, 0L, 0L, 1L, 0L, 1L)),
        Arguments.of("nonzero validity", List.of(0L, 0L, 0L, 1L, 1L, 1L, 1L)));
  }

  private static Stream<Arguments> invalidNumericValues() {
    return Stream.of(
        Arguments.of("not numeric", "one"),
        Arguments.of("not finite", Double.POSITIVE_INFINITY),
        Arguments.of("fractional", 1.5D),
        Arguments.of("negative", -1L));
  }
}
