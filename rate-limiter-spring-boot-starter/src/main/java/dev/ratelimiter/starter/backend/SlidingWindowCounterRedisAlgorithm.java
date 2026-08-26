package dev.ratelimiter.starter.backend;

import dev.ratelimiter.core.RateLimitPolicy;
import dev.ratelimiter.core.SlidingWindowCounterPolicy;
import dev.ratelimiter.starter.hash.RedisKeyFactory;
import dev.ratelimiter.starter.metrics.RateLimiterMetrics;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class SlidingWindowCounterRedisAlgorithm extends AbstractRedisAlgorithm {
  private final RedisKeyFactory keys;
  private final LuaScriptRegistry scripts;

  public SlidingWindowCounterRedisAlgorithm(
      StringRedisTemplate redis,
      RedisKeyFactory keys,
      LuaScriptRegistry scripts,
      RateLimiterMetrics metrics) {
    super(redis, metrics);
    this.keys = keys;
    this.scripts = scripts;
  }

  @Override
  public BackendDecision reserve(
      RateLimitPolicy policy, String digest, int minimumPermits, int desiredPermits) {
    if (!(policy instanceof SlidingWindowCounterPolicy counter)) {
      throw new IllegalArgumentException(
          "Sliding-counter strategy received a different policy type");
    }
    return execute(
        policy,
        scripts.slidingWindowCounter(),
        List.of(keys.slidingWindowCounter(digest)),
        minimumPermits,
        desiredPermits,
        Integer.toString(counter.limit()),
        Long.toString(counter.window().toMillis()),
        Integer.toString(minimumPermits),
        Integer.toString(desiredPermits));
  }
}
