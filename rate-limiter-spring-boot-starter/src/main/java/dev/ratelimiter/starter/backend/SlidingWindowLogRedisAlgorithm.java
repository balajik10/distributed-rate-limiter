package dev.ratelimiter.starter.backend;

import dev.ratelimiter.core.RateLimitPolicy;
import dev.ratelimiter.core.SlidingWindowLogPolicy;
import dev.ratelimiter.starter.hash.RedisKeyFactory;
import dev.ratelimiter.starter.metrics.RateLimiterMetrics;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class SlidingWindowLogRedisAlgorithm extends AbstractRedisAlgorithm {
  private final RedisKeyFactory keys;
  private final LuaScriptRegistry scripts;

  public SlidingWindowLogRedisAlgorithm(
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
    if (!(policy instanceof SlidingWindowLogPolicy log)) {
      throw new IllegalArgumentException("Sliding-log strategy received a different policy type");
    }
    return execute(
        policy,
        scripts.slidingWindowLog(),
        keys.slidingWindowLog(digest),
        minimumPermits,
        desiredPermits,
        Integer.toString(log.limit()),
        Long.toString(log.window().toMillis()),
        Integer.toString(minimumPermits),
        Integer.toString(desiredPermits));
  }
}
