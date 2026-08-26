package dev.ratelimiter.service.api;

import dev.ratelimiter.core.LocalCacheSettings;

public record LocalCacheView(
    boolean enabled,
    int maxLeaseSize,
    long maxLeaseTtlMs,
    int expectedMaxInstances,
    long maxErrorPermits) {

  static LocalCacheView from(LocalCacheSettings settings) {
    return new LocalCacheView(
        settings.enabled(),
        settings.maxLeaseSize(),
        settings.maxLeaseTtl().toMillis(),
        settings.expectedMaxInstances(),
        settings.maxErrorPermits());
  }
}
