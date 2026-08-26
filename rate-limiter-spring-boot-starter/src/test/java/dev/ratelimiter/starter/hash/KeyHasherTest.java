package dev.ratelimiter.starter.hash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KeyHasherTest {
  @Test
  void producesStableSha256AndHmacDigests() {
    assertThat(new KeyHasher(null).digest("api-standard", 7, "user:123"))
        .isEqualTo("d3d35f8136cef8f878d87f9432fb6571583adf41072ff77acc5d395d2bb8e440");
    assertThat(new KeyHasher("test-secret").digest("api-standard", 7, "user:123"))
        .isEqualTo("a40c037dd9ce4f27f3c62a4c19e91962efedfde2792780c7344e4a77b62fd830");
  }

  @Test
  void policyVersionsProduceIsolatedClusterSafeKeysWithoutRawSubject() {
    KeyHasher hasher = new KeyHasher(null);
    RedisKeyFactory keys = new RedisKeyFactory("custom");
    String first = hasher.digest("api-standard", 1, "private:user:123");
    String second = hasher.digest("api-standard", 2, "private:user:123");

    assertThat(first).isNotEqualTo(second);
    assertThat(keys.tokenBucket(first))
        .isEqualTo("custom:{" + first + "}:tb")
        .doesNotContain("private", "user:123");
    assertThat(keys.slidingWindowLog(first))
        .allSatisfy(key -> assertThat(key).contains("{" + first + "}"));
  }

  @Test
  void rejectsPrefixesAndDigestsThatCouldBreakTheHashTag() {
    assertThatThrownBy(() -> new RedisKeyFactory("rl:{injected}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RedisKeyFactory("rl").tokenBucket("raw-key"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
