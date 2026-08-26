package dev.ratelimiter.starter.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.ratelimiter.core.FailureMode;
import dev.ratelimiter.core.LocalCacheSettings;
import dev.ratelimiter.core.TokenBucketPolicy;
import dev.ratelimiter.starter.hash.RedisKeyFactory;
import dev.ratelimiter.starter.metrics.RateLimiterMetrics;
import io.lettuce.core.RedisCommandTimeoutException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisNoRetryTest {
  @Test
  @SuppressWarnings("unchecked")
  void ambiguousTimeoutIsClassifiedWithoutRetryingTheLuaScript() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    RedisCommandTimeoutException timeout = new RedisCommandTimeoutException("timed out");
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(timeout);

    RedisKeyFactory keys = new RedisKeyFactory("no-retry");
    LuaScriptRegistry scripts = new LuaScriptRegistry();
    RateLimiterMetrics metrics = new RateLimiterMetrics(new SimpleMeterRegistry());
    RedisRateLimitBackend backend =
        new RedisRateLimitBackend(
            new TokenBucketRedisAlgorithm(redis, keys, scripts, metrics),
            new SlidingWindowLogRedisAlgorithm(redis, keys, scripts, metrics),
            new SlidingWindowCounterRedisAlgorithm(redis, keys, scripts, metrics),
            new RedisAvailabilityClassifier(),
            metrics);
    TokenBucketPolicy policy =
        new TokenBucketPolicy(
            "timeout-no-retry",
            1,
            10,
            10,
            Duration.ofSeconds(1),
            FailureMode.FAIL_CLOSED,
            LocalCacheSettings.disabled());

    assertThatThrownBy(() -> backend.reserve(policy, "a".repeat(64), 1, 1))
        .isInstanceOfSatisfying(
            BackendUnavailableException.class,
            failure -> {
              assertThat(failure.category())
                  .isEqualTo(BackendUnavailableException.Category.AMBIGUOUS_EXECUTION);
              assertThat(failure.getCause()).isSameAs(timeout);
            });
    verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
  }
}
