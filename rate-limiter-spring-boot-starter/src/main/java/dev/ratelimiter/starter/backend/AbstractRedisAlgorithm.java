package dev.ratelimiter.starter.backend;

import dev.ratelimiter.core.RateLimitPolicy;
import dev.ratelimiter.starter.metrics.RateLimiterMetrics;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

abstract class AbstractRedisAlgorithm implements RedisRateLimitAlgorithm {
  private final StringRedisTemplate redis;
  private final RateLimiterMetrics metrics;

  AbstractRedisAlgorithm(StringRedisTemplate redis, RateLimiterMetrics metrics) {
    this.redis = redis;
    this.metrics = metrics;
  }

  final BackendDecision execute(
      RateLimitPolicy policy,
      RedisScript<List> script,
      List<String> keys,
      int minimumPermits,
      int desiredPermits,
      String... arguments) {
    long started = metrics.start();
    boolean success = false;
    try {
      Object result = redis.execute(script, keys, (Object[]) arguments);
      BackendDecision decoded = BackendDecision.decode(result, minimumPermits, desiredPermits);
      success = true;
      return decoded;
    } finally {
      metrics.redisCall(policy, started, success);
    }
  }
}
