package dev.ratelimiter.starter.backend;

import dev.ratelimiter.core.RateLimitPolicy;
import dev.ratelimiter.starter.metrics.RateLimiterMetrics;
import java.util.EnumMap;
import java.util.Map;

public class RedisRateLimitBackend {
  private final Map<dev.ratelimiter.core.Algorithm, RedisRateLimitAlgorithm> algorithms;
  private final RedisAvailabilityClassifier availabilityClassifier;
  private final RateLimiterMetrics metrics;

  public RedisRateLimitBackend(
      TokenBucketRedisAlgorithm tokenBucket,
      SlidingWindowLogRedisAlgorithm slidingLog,
      SlidingWindowCounterRedisAlgorithm slidingCounter,
      RedisAvailabilityClassifier availabilityClassifier,
      RateLimiterMetrics metrics) {
    this.algorithms = new EnumMap<>(dev.ratelimiter.core.Algorithm.class);
    algorithms.put(dev.ratelimiter.core.Algorithm.TOKEN_BUCKET, tokenBucket);
    algorithms.put(dev.ratelimiter.core.Algorithm.SLIDING_WINDOW_LOG, slidingLog);
    algorithms.put(dev.ratelimiter.core.Algorithm.SLIDING_WINDOW_COUNTER, slidingCounter);
    this.availabilityClassifier = availabilityClassifier;
    this.metrics = metrics;
  }

  public BackendDecision reserve(
      RateLimitPolicy policy, String subjectDigest, int minimumPermits, int desiredPermits) {
    if (minimumPermits < 1 || desiredPermits < minimumPermits || desiredPermits > policy.limit()) {
      throw new IllegalArgumentException("Invalid backend reservation bounds");
    }
    try {
      return algorithms
          .get(policy.algorithm())
          .reserve(policy, subjectDigest, minimumPermits, desiredPermits);
    } catch (BackendUnavailableException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      var category = availabilityClassifier.classify(failure);
      if (category.isEmpty()) {
        throw failure;
      }
      metrics.redisError(policy, category.get());
      throw new BackendUnavailableException(category.get(), failure);
    }
  }
}
