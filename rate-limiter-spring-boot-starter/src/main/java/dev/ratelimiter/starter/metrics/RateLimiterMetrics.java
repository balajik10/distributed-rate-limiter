package dev.ratelimiter.starter.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import dev.ratelimiter.core.DecisionSource;
import dev.ratelimiter.core.RateLimitDecision;
import dev.ratelimiter.core.RateLimitPolicy;
import dev.ratelimiter.starter.backend.BackendUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

public final class RateLimiterMetrics {
  public static final String DECISIONS = "ratelimiter.decisions";
  public static final String DECISION_DURATION = "ratelimiter.decision.duration";
  public static final String REDIS_SCRIPT_DURATION = "ratelimiter.redis.script.duration";
  public static final String REDIS_CALLS = "ratelimiter.redis.calls";
  public static final String REDIS_ERRORS = "ratelimiter.redis.errors";
  public static final String CACHE_HITS = "ratelimiter.local.cache.hits";
  public static final String CACHE_MISSES = "ratelimiter.local.cache.misses";
  public static final String CACHE_RESERVATIONS = "ratelimiter.local.cache.reservations";
  public static final String CACHE_EVICTIONS = "ratelimiter.local.cache.evictions";
  public static final String PERMITS_RESERVED = "ratelimiter.local.permits.reserved";
  public static final String PERMITS_CONSUMED = "ratelimiter.local.permits.consumed";
  public static final String PERMITS_WASTED = "ratelimiter.local.permits.wasted";
  public static final String CURRENT_CACHED_TAIL = "ratelimiter.local.permits.current";
  public static final String FALLBACK_ACTIVATIONS = "ratelimiter.fallback.activations";

  private final MeterRegistry registry;
  private final AtomicLong currentCachedTail = new AtomicLong();

  public RateLimiterMetrics(MeterRegistry registry) {
    this.registry = registry;
    Gauge.builder(CURRENT_CACHED_TAIL, currentCachedTail, AtomicLong::get)
        .description("Current count of locally cached, already charged permits")
        .register(registry);
  }

  public long start() {
    return System.nanoTime();
  }

  public void monitorCache(Cache<?, ?> cache, String cacheName) {
    CaffeineCacheMetrics.monitor(registry, cache, cacheName);
  }

  public void redisCall(RateLimitPolicy policy, long startedNanos, boolean success) {
    String outcome = success ? "success" : "error";
    Counter.builder(REDIS_CALLS)
        .tag("policy", policy.id())
        .tag("algorithm", policy.algorithm().name())
        .tag("outcome", outcome)
        .register(registry)
        .increment();
    Timer.builder(REDIS_SCRIPT_DURATION)
        .tag("policy", policy.id())
        .tag("algorithm", policy.algorithm().name())
        .tag("outcome", outcome)
        .publishPercentileHistogram()
        .minimumExpectedValue(Duration.ofNanos(10_000))
        .maximumExpectedValue(Duration.ofSeconds(5))
        .register(registry)
        .record(System.nanoTime() - startedNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
  }

  public void redisError(RateLimitPolicy policy, BackendUnavailableException.Category category) {
    Counter.builder(REDIS_ERRORS)
        .tag("policy", policy.id())
        .tag("algorithm", policy.algorithm().name())
        .tag("category", category.name())
        .register(registry)
        .increment();
  }

  public void decision(RateLimitPolicy policy, RateLimitDecision decision, long startedNanos) {
    String outcome = decision.allowed() ? "allowed" : "denied";
    Counter.builder(DECISIONS)
        .tag("policy", policy.id())
        .tag("algorithm", policy.algorithm().name())
        .tag("outcome", outcome)
        .tag("source", decision.source().name())
        .tag("failure_mode", policy.failureMode().name())
        .register(registry)
        .increment();
    Timer.builder(DECISION_DURATION)
        .tag("policy", policy.id())
        .tag("algorithm", policy.algorithm().name())
        .tag("outcome", outcome)
        .tag("source", decision.source().name())
        .publishPercentileHistogram()
        .minimumExpectedValue(Duration.ofNanos(10_000))
        .maximumExpectedValue(Duration.ofSeconds(5))
        .register(registry)
        .record(System.nanoTime() - startedNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
  }

  public void fallback(RateLimitPolicy policy, DecisionSource source) {
    Counter.builder(FALLBACK_ACTIVATIONS)
        .tag("policy", policy.id())
        .tag("algorithm", policy.algorithm().name())
        .tag("source", source.name())
        .tag("failure_mode", policy.failureMode().name())
        .register(registry)
        .increment();
  }

  public void cacheHit(RateLimitPolicy policy) {
    counter(CACHE_HITS, policy).increment();
    counter(PERMITS_CONSUMED, policy).increment();
    currentCachedTail.decrementAndGet();
  }

  public void cacheMiss(RateLimitPolicy policy) {
    counter(CACHE_MISSES, policy).increment();
  }

  public void reservation(RateLimitPolicy policy, int tail) {
    counter(CACHE_RESERVATIONS, policy).increment();
    counter(PERMITS_RESERVED, policy).increment(tail);
    currentCachedTail.addAndGet(tail);
  }

  public void wasted(RateLimitPolicy policy, int permits, boolean eviction) {
    if (permits <= 0) {
      return;
    }
    counter(PERMITS_WASTED, policy).increment(permits);
    if (eviction) {
      counter(CACHE_EVICTIONS, policy).increment();
    }
    currentCachedTail.addAndGet(-permits);
  }

  private Counter counter(String name, RateLimitPolicy policy) {
    return Counter.builder(name)
        .tag("policy", policy.id())
        .tag("algorithm", policy.algorithm().name())
        .register(registry);
  }
}
