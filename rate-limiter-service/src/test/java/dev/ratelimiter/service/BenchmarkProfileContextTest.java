package dev.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ratelimiter.core.Algorithm;
import dev.ratelimiter.core.PolicyProvider;
import dev.ratelimiter.core.RateLimitPolicy;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {"spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1"})
@ActiveProfiles("benchmark")
class BenchmarkProfileContextTest {

  @Autowired PolicyProvider policyProvider;

  @Test
  void benchmarkProfileLoadsMatchedStrictAndBatchTenPairs() {
    Map<String, RateLimitPolicy> policies =
        policyProvider.policies().stream()
            .collect(Collectors.toMap(RateLimitPolicy::id, Function.identity()));

    assertThat(policies).hasSize(9);
    assertMatchedPair(policies, "benchmark-token", Algorithm.TOKEN_BUCKET);
    assertMatchedPair(policies, "benchmark-log", Algorithm.SLIDING_WINDOW_LOG);
    assertMatchedPair(policies, "benchmark-counter", Algorithm.SLIDING_WINDOW_COUNTER);
  }

  private static void assertMatchedPair(
      Map<String, RateLimitPolicy> policies, String prefix, Algorithm algorithm) {
    RateLimitPolicy strict = policies.get(prefix + "-strict");
    RateLimitPolicy leased = policies.get(prefix + "-leased");

    assertThat(strict).isNotNull();
    assertThat(leased).isNotNull();
    assertThat(strict.algorithm()).isEqualTo(algorithm);
    assertThat(leased.algorithm()).isEqualTo(algorithm);
    assertThat(strict.limit()).isEqualTo(leased.limit());
    assertThat(strict.failureMode()).isEqualTo(leased.failureMode());
    assertThat(strict.localCacheSettings().enabled()).isFalse();
    assertThat(strict.localCacheSettings().maxLeaseSize()).isEqualTo(1);
    assertThat(leased.localCacheSettings().enabled()).isTrue();
    assertThat(leased.localCacheSettings().maxLeaseSize()).isEqualTo(10);
  }
}
