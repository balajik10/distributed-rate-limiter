package dev.ratelimiter.starter.backend;

import dev.ratelimiter.core.RateLimitPolicy;
import dev.ratelimiter.core.TokenBucketPolicy;
import dev.ratelimiter.starter.hash.RedisKeyFactory;
import dev.ratelimiter.starter.metrics.RateLimiterMetrics;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class TokenBucketRedisAlgorithm extends AbstractRedisAlgorithm {
  private final RedisKeyFactory keys;
  private final LuaScriptRegistry scripts;

  public TokenBucketRedisAlgorithm(
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
    if (!(policy instanceof TokenBucketPolicy tokenBucket)) {
      throw new IllegalArgumentException("Token-bucket strategy received a different policy type");
    }
    return execute(
        policy,
        scripts.tokenBucket(),
        List.of(keys.tokenBucket(digest)),
        minimumPermits,
        desiredPermits,
        Integer.toString(tokenBucket.capacity()),
        Integer.toString(tokenBucket.refillTokens()),
        Long.toString(tokenBucket.refillPeriod().toMillis()),
        Integer.toString(minimumPermits),
        Integer.toString(desiredPermits));
  }
}
