package dev.ratelimiter.starter.backend;

import dev.ratelimiter.core.RateLimitPolicy;

interface RedisRateLimitAlgorithm {
  BackendDecision reserve(
      RateLimitPolicy policy, String subjectDigest, int minimumPermits, int desiredPermits);
}
