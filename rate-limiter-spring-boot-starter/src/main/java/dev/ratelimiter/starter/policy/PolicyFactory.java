package dev.ratelimiter.starter.policy;

import dev.ratelimiter.core.Algorithm;
import dev.ratelimiter.core.LocalCacheSettings;
import dev.ratelimiter.core.RateLimitPolicy;
import dev.ratelimiter.core.SlidingWindowCounterPolicy;
import dev.ratelimiter.core.SlidingWindowLogPolicy;
import dev.ratelimiter.core.TokenBucketPolicy;
import dev.ratelimiter.starter.config.PolicyProperties;
import java.time.Duration;
import java.util.Objects;

public final class PolicyFactory {
  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  private static final Duration MIN_DURATION = Duration.ofMillis(10);
  private static final Duration MAX_DURATION = Duration.ofHours(24);
  private static final Duration MAX_EMPTY_REFILL = Duration.ofDays(30);

  public RateLimitPolicy create(String id, PolicyProperties source) {
    Objects.requireNonNull(source, "policy configuration");
    long version = required(source.getVersion(), "version", id);
    Algorithm algorithm = required(source.getAlgorithm(), "algorithm", id);
    var failureMode = required(source.getFailureMode(), "failure-mode", id);
    LocalCacheSettings localCache = localCache(id, source.getLocalCache());

    return switch (algorithm) {
      case TOKEN_BUCKET -> {
        reject(source.getLimit() != null || source.getWindow() != null, id, "window fields");
        int capacity = required(source.getCapacity(), "capacity", id);
        int refillTokens = required(source.getRefillTokens(), "refill-tokens", id);
        Duration refillPeriod = required(source.getRefillPeriod(), "refill-period", id);
        validateDuration(id, "refill-period", refillPeriod);
        requireSafeProduct(id, capacity, refillPeriod.toMillis());
        long refillEmptyMs =
            ceilDiv(Math.multiplyExact((long) capacity, refillPeriod.toMillis()), refillTokens);
        reject(
            refillEmptyMs > MAX_EMPTY_REFILL.toMillis(), id, "empty-bucket refill exceeds 30 days");
        yield new TokenBucketPolicy(
            id, version, capacity, refillTokens, refillPeriod, failureMode, localCache);
      }
      case SLIDING_WINDOW_LOG -> {
        reject(
            source.getCapacity() != null
                || source.getRefillTokens() != null
                || source.getRefillPeriod() != null,
            id,
            "token-bucket fields");
        int limit = required(source.getLimit(), "limit", id);
        Duration window = required(source.getWindow(), "window", id);
        validateDuration(id, "window", window);
        requireSafeProduct(id, limit, window.toMillis());
        validateWindowLease(id, localCache, window);
        yield new SlidingWindowLogPolicy(id, version, limit, window, failureMode, localCache);
      }
      case SLIDING_WINDOW_COUNTER -> {
        reject(
            source.getCapacity() != null
                || source.getRefillTokens() != null
                || source.getRefillPeriod() != null,
            id,
            "token-bucket fields");
        int limit = required(source.getLimit(), "limit", id);
        Duration window = required(source.getWindow(), "window", id);
        validateDuration(id, "window", window);
        requireSafeProduct(id, limit, window.toMillis());
        validateWindowLease(id, localCache, window);
        yield new SlidingWindowCounterPolicy(id, version, limit, window, failureMode, localCache);
      }
    };
  }

  private LocalCacheSettings localCache(String id, PolicyProperties.LocalCacheProperties source) {
    if (source == null) {
      throw invalid(id, "local-cache is required");
    }
    reject(
        source.getMaxLeaseSize() < 1 || source.getMaxLeaseSize() > 100,
        id,
        "invalid max-lease-size");
    reject(source.getExpectedMaxInstances() < 1, id, "invalid expected-max-instances");
    reject(source.getMaxErrorPermits() < 0, id, "invalid max-error-permits");
    reject(
        source.getMaxLeaseTtl() == null
            || source.getMaxLeaseTtl().isZero()
            || source.getMaxLeaseTtl().isNegative(),
        id,
        "invalid max-lease-ttl");
    if (source.isEnabled()) {
      long allowedBatch = source.getMaxErrorPermits() / source.getExpectedMaxInstances() + 1;
      reject(
          source.getMaxLeaseSize() > allowedBatch,
          id,
          "max-lease-size exceeds configured approximation budget");
    }
    return new LocalCacheSettings(
        source.isEnabled(),
        source.getMaxLeaseSize(),
        source.getMaxLeaseTtl(),
        source.getExpectedMaxInstances(),
        source.getMaxErrorPermits());
  }

  private static void validateWindowLease(String id, LocalCacheSettings settings, Duration window) {
    if (settings.enabled()) {
      reject(
          settings.maxLeaseTtl().compareTo(window.dividedBy(10)) > 0,
          id,
          "max-lease-ttl must be at most window/10");
    }
  }

  private static void validateDuration(String id, String name, Duration duration) {
    reject(
        duration.compareTo(MIN_DURATION) < 0 || duration.compareTo(MAX_DURATION) > 0,
        id,
        name + " must be between 10ms and 24h");
  }

  private static void requireSafeProduct(String id, long left, long right) {
    reject(
        left <= 0 || right <= 0 || left > (MAX_SAFE_INTEGER - 1) / right,
        id,
        "unsafe Lua numeric product");
  }

  private static long ceilDiv(long numerator, long denominator) {
    return numerator == 0 ? 0 : (numerator - 1) / denominator + 1;
  }

  private static <T> T required(T value, String field, String id) {
    if (value == null) {
      throw invalid(id, field + " is required");
    }
    return value;
  }

  private static void reject(boolean rejected, String id, String message) {
    if (rejected) {
      throw invalid(id, message);
    }
  }

  private static IllegalStateException invalid(String id, String message) {
    return new IllegalStateException("Invalid rate-limiter policy '" + id + "': " + message);
  }
}
