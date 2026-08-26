package dev.ratelimiter.starter.hash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HashBoundaryBranchesTest {
  @Test
  void blankHmacSecretDeliberatelySelectsPlainSha256() {
    KeyHasher plain = new KeyHasher(null);
    KeyHasher blank = new KeyHasher(" \t ");

    assertThat(blank.usesHmac()).isFalse();
    assertThat(blank.digest("policy", 9, "sensitive-subject"))
        .isEqualTo(plain.digest("policy", 9, "sensitive-subject"));
  }

  @Test
  void nullPrefixAndDigestAreRejectedBeforePatternMatching() {
    assertThatThrownBy(() -> new RedisKeyFactory(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("key-prefix");

    RedisKeyFactory keys = new RedisKeyFactory("rl");
    assertThatThrownBy(() -> keys.slidingWindowCounter(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("full lowercase SHA-256");
  }

  @Test
  void allPhysicalKeyFormsShareExactlyOneDigestHashTag() {
    RedisKeyFactory keys = new RedisKeyFactory("rl.safe-1");
    String digest = "0123456789abcdef".repeat(4);

    assertThat(keys.tokenBucket(digest)).isEqualTo("rl.safe-1:{" + digest + "}:tb");
    assertThat(keys.slidingWindowCounter(digest)).isEqualTo("rl.safe-1:{" + digest + "}:swc");
    assertThat(keys.slidingWindowLog(digest))
        .containsExactly(
            "rl.safe-1:{" + digest + "}:swl:events", "rl.safe-1:{" + digest + "}:swl:meta");
  }
}
