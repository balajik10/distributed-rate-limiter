package dev.ratelimiter.starter.backend;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

public final class RateLimiterRedisHealthIndicator implements HealthIndicator {
  private final RedisConnectionFactory connectionFactory;

  public RateLimiterRedisHealthIndicator(RedisConnectionFactory connectionFactory) {
    this.connectionFactory = connectionFactory;
  }

  @Override
  public Health health() {
    try (RedisConnection connection = connectionFactory.getConnection()) {
      String response = connection.ping();
      if ("PONG".equalsIgnoreCase(response)) {
        return Health.up().withDetail("backend", "redis").build();
      }
      return Health.down().withDetail("backend", "redis").build();
    } catch (RuntimeException unavailable) {
      return Health.down().withDetail("backend", "redis").build();
    }
  }
}
