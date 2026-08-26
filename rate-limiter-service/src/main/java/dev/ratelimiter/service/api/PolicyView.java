package dev.ratelimiter.service.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.ratelimiter.core.Algorithm;
import dev.ratelimiter.core.FailureMode;
import dev.ratelimiter.core.RateLimitPolicy;
import dev.ratelimiter.core.SlidingWindowCounterPolicy;
import dev.ratelimiter.core.SlidingWindowLogPolicy;
import dev.ratelimiter.core.TokenBucketPolicy;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyView(
    String id,
    long version,
    Algorithm algorithm,
    FailureMode failureMode,
    int limit,
    TokenBucketView tokenBucket,
    SlidingWindowView slidingWindow,
    LocalCacheView localCache) {

  public static PolicyView from(RateLimitPolicy policy) {
    if (policy instanceof TokenBucketPolicy tokenBucket) {
      return new PolicyView(
          tokenBucket.id(),
          tokenBucket.version(),
          tokenBucket.algorithm(),
          tokenBucket.failureMode(),
          tokenBucket.limit(),
          new TokenBucketView(
              tokenBucket.capacity(),
              tokenBucket.refillTokens(),
              tokenBucket.refillPeriod().toMillis()),
          null,
          LocalCacheView.from(tokenBucket.localCacheSettings()));
    }
    if (policy instanceof SlidingWindowLogPolicy slidingLog) {
      return slidingWindowView(slidingLog, slidingLog.window().toMillis());
    }
    if (policy instanceof SlidingWindowCounterPolicy slidingCounter) {
      return slidingWindowView(slidingCounter, slidingCounter.window().toMillis());
    }
    throw new IllegalArgumentException("Unsupported policy type");
  }

  private static PolicyView slidingWindowView(RateLimitPolicy policy, long windowMs) {
    return new PolicyView(
        policy.id(),
        policy.version(),
        policy.algorithm(),
        policy.failureMode(),
        policy.limit(),
        null,
        new SlidingWindowView(policy.limit(), windowMs),
        LocalCacheView.from(policy.localCacheSettings()));
  }

  public record TokenBucketView(int capacity, int refillTokens, long refillPeriodMs) {}

  public record SlidingWindowView(int limit, long windowMs) {}
}
