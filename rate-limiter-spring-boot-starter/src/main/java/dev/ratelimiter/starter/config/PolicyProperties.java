package dev.ratelimiter.starter.config;

import dev.ratelimiter.core.Algorithm;
import dev.ratelimiter.core.FailureMode;
import java.time.Duration;

public class PolicyProperties {
  private Long version;
  private Algorithm algorithm;
  private FailureMode failureMode;
  private Integer capacity;
  private Integer refillTokens;
  private Duration refillPeriod;
  private Integer limit;
  private Duration window;
  private LocalCacheProperties localCache = new LocalCacheProperties();

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }

  public Algorithm getAlgorithm() {
    return algorithm;
  }

  public void setAlgorithm(Algorithm algorithm) {
    this.algorithm = algorithm;
  }

  public FailureMode getFailureMode() {
    return failureMode;
  }

  public void setFailureMode(FailureMode failureMode) {
    this.failureMode = failureMode;
  }

  public Integer getCapacity() {
    return capacity;
  }

  public void setCapacity(Integer capacity) {
    this.capacity = capacity;
  }

  public Integer getRefillTokens() {
    return refillTokens;
  }

  public void setRefillTokens(Integer refillTokens) {
    this.refillTokens = refillTokens;
  }

  public Duration getRefillPeriod() {
    return refillPeriod;
  }

  public void setRefillPeriod(Duration refillPeriod) {
    this.refillPeriod = refillPeriod;
  }

  public Integer getLimit() {
    return limit;
  }

  public void setLimit(Integer limit) {
    this.limit = limit;
  }

  public Duration getWindow() {
    return window;
  }

  public void setWindow(Duration window) {
    this.window = window;
  }

  public LocalCacheProperties getLocalCache() {
    return localCache;
  }

  public void setLocalCache(LocalCacheProperties localCache) {
    this.localCache = localCache;
  }

  public static class LocalCacheProperties {
    private boolean enabled;
    private int maxLeaseSize = 1;
    private Duration maxLeaseTtl = Duration.ofMillis(100);
    private int expectedMaxInstances = 1;
    private long maxErrorPermits;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getMaxLeaseSize() {
      return maxLeaseSize;
    }

    public void setMaxLeaseSize(int maxLeaseSize) {
      this.maxLeaseSize = maxLeaseSize;
    }

    public Duration getMaxLeaseTtl() {
      return maxLeaseTtl;
    }

    public void setMaxLeaseTtl(Duration maxLeaseTtl) {
      this.maxLeaseTtl = maxLeaseTtl;
    }

    public int getExpectedMaxInstances() {
      return expectedMaxInstances;
    }

    public void setExpectedMaxInstances(int expectedMaxInstances) {
      this.expectedMaxInstances = expectedMaxInstances;
    }

    public long getMaxErrorPermits() {
      return maxErrorPermits;
    }

    public void setMaxErrorPermits(long maxErrorPermits) {
      this.maxErrorPermits = maxErrorPermits;
    }
  }
}
