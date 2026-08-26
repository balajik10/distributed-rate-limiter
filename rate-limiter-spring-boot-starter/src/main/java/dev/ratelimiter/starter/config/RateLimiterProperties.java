package dev.ratelimiter.starter.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rate-limiter")
public class RateLimiterProperties {
  private String keyPrefix = "rl";
  private String keyHashSecret;
  private long cacheMaximumSize = 100_000;
  private Duration rampUpDuration = Duration.ofSeconds(2);
  private int lockStripes = 4096;
  private RedisSettings redis = new RedisSettings();
  private Map<String, PolicyProperties> policies = new LinkedHashMap<>();

  public String getKeyPrefix() {
    return keyPrefix;
  }

  public void setKeyPrefix(String keyPrefix) {
    this.keyPrefix = keyPrefix;
  }

  public String getKeyHashSecret() {
    return keyHashSecret;
  }

  public void setKeyHashSecret(String keyHashSecret) {
    this.keyHashSecret = keyHashSecret;
  }

  public long getCacheMaximumSize() {
    return cacheMaximumSize;
  }

  public void setCacheMaximumSize(long cacheMaximumSize) {
    this.cacheMaximumSize = cacheMaximumSize;
  }

  public Duration getRampUpDuration() {
    return rampUpDuration;
  }

  public void setRampUpDuration(Duration rampUpDuration) {
    this.rampUpDuration = rampUpDuration;
  }

  public int getLockStripes() {
    return lockStripes;
  }

  public void setLockStripes(int lockStripes) {
    this.lockStripes = lockStripes;
  }

  public RedisSettings getRedis() {
    return redis;
  }

  public void setRedis(RedisSettings redis) {
    this.redis = redis;
  }

  public Map<String, PolicyProperties> getPolicies() {
    return policies;
  }

  public void setPolicies(Map<String, PolicyProperties> policies) {
    this.policies = policies;
  }

  public static class RedisSettings {
    private Duration connectTimeout = Duration.ofMillis(500);
    private Duration commandTimeout = Duration.ofMillis(500);

    public Duration getConnectTimeout() {
      return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
      this.connectTimeout = connectTimeout;
    }

    public Duration getCommandTimeout() {
      return commandTimeout;
    }

    public void setCommandTimeout(Duration commandTimeout) {
      this.commandTimeout = commandTimeout;
    }
  }
}
