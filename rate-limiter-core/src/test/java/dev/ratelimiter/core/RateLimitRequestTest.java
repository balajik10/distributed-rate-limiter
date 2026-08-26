package dev.ratelimiter.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class RateLimitRequestTest {

  @Test
  void acceptsValidRequestAndBoundaryPermitCounts() {
    assertThat(new RateLimitRequest("api-standard", "user:123", 1))
        .isEqualTo(new RateLimitRequest("api-standard", "user:123", 1));
    assertThat(new RateLimitRequest("a", "key", 100).permits()).isEqualTo(100);
  }

  @Test
  void countsUnicodeCodePointsRatherThanUtf16CodeUnits() {
    String exactly256Characters = "🚀".repeat(256);

    assertThat(new RateLimitRequest("p", exactly256Characters, 1).key())
        .isEqualTo(exactly256Characters);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new RateLimitRequest("p", exactly256Characters + "x", 1));
  }

  @Test
  void rejectsMalformedPolicyIds() {
    for (String invalid :
        new String[] {"", "Upper", "-leading", "contains space", "a".repeat(65)}) {
      assertThatIllegalArgumentException()
          .as("policyId=%s", invalid)
          .isThrownBy(() -> new RateLimitRequest(invalid, "key", 1))
          .withMessageContaining("policyId");
    }
    assertThatNullPointerException()
        .isThrownBy(() -> new RateLimitRequest(null, "key", 1))
        .withMessageContaining("policyId");
  }

  @Test
  void rejectsMissingBlankAndOversizedKeysWithoutEchoingThem() {
    String oversized = "secret-" + "x".repeat(250);

    assertThatNullPointerException()
        .isThrownBy(() -> new RateLimitRequest("p", null, 1))
        .withMessageContaining("key");
    assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitRequest("p", "   ", 1));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new RateLimitRequest("p", oversized, 1))
        .withMessageNotContaining(oversized);
  }

  @Test
  void rejectsPermitCountsOutsidePublicRequestRange() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new RateLimitRequest("p", "key", 0))
        .withMessageContaining("permits");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new RateLimitRequest("p", "key", 101))
        .withMessageContaining("permits");
  }
}
