package dev.ratelimiter.starter.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.ratelimiter.core.FailureMode;
import dev.ratelimiter.core.LocalCacheSettings;
import dev.ratelimiter.core.TokenBucketPolicy;
import dev.ratelimiter.starter.backend.BackendDecision;
import dev.ratelimiter.starter.backend.RedisAvailabilityClassifier;
import dev.ratelimiter.starter.backend.RedisRateLimitBackend;
import dev.ratelimiter.starter.config.RateLimiterProperties;
import dev.ratelimiter.starter.metrics.RateLimiterMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class LocalLeaseManagerTest {
  @Test
  void usesTheExactAdaptiveSequence() {
    List<Integer> batches = Collections.synchronizedList(new ArrayList<>());
    RedisRateLimitBackend backend =
        backend(
            (policy, digest, minimum, desired) -> {
              batches.add(desired);
              return allowed(desired);
            });
    LocalLeaseManager manager = manager(backend, new FakeTicker(), new SimpleMeterRegistry(), 10);

    for (int request = 0; request < 16; request++) {
      assertThat(manager.acquireUnit(policy(10), DIGEST).allowed()).isTrue();
    }

    assertThat(batches).containsExactly(1, 2, 4, 8, 10);
  }

  @Test
  void adaptiveRampUsesTheActualRedisGrantRatherThanTheRequestedBatch() {
    List<Integer> batches = new ArrayList<>();
    AtomicInteger calls = new AtomicInteger();
    RedisRateLimitBackend backend =
        backend(
            (policy, digest, minimum, desired) -> {
              batches.add(desired);
              int reserved = calls.getAndIncrement() == 1 ? 1 : desired;
              return allowed(reserved);
            });
    LocalLeaseManager manager = manager(backend, new FakeTicker(), new SimpleMeterRegistry(), 10);

    manager.acquireUnit(policy(10), DIGEST);
    manager.acquireUnit(policy(10), DIGEST);
    manager.acquireUnit(policy(10), DIGEST);

    assertThat(batches).containsExactly(1, 2, 2);
  }

  @Test
  void serializesRefillsAndAtomicallyServesOneHundredConcurrentThreads() throws Exception {
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximumActive = new AtomicInteger();
    AtomicInteger charged = new AtomicInteger();
    RedisRateLimitBackend backend =
        backend(
            (policy, digest, minimum, desired) -> {
              int current = active.incrementAndGet();
              maximumActive.accumulateAndGet(current, Math::max);
              try {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
                charged.addAndGet(desired);
                return allowed(desired);
              } finally {
                active.decrementAndGet();
              }
            });
    LocalLeaseManager manager = manager(backend, new FakeTicker(), new SimpleMeterRegistry(), 100);
    ExecutorService executor = Executors.newFixedThreadPool(100);
    CountDownLatch ready = new CountDownLatch(100);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<LeaseAcquisition>> calls = new ArrayList<>();
    try {
      for (int index = 0; index < 100; index++) {
        calls.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return manager.acquireUnit(policy(100), DIGEST);
                }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (Future<LeaseAcquisition> call : calls) {
        assertThat(call.get(5, TimeUnit.SECONDS).allowed()).isTrue();
      }
    } finally {
      executor.shutdownNow();
    }

    assertThat(maximumActive).hasValue(1);
    assertThat(charged.get()).isGreaterThanOrEqualTo(100);
    assertThat(charged.get() - manager.estimatedCachedPermits()).isEqualTo(100);
  }

  @Test
  void shutdownCountsUnusedPermitsAsWasteAndNeverRefundsThem() throws Exception {
    RedisRateLimitBackend backend = backend((policy, digest, minimum, desired) -> allowed(desired));
    FakeTicker ticker = new FakeTicker();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    LocalLeaseManager manager = manager(backend, ticker, registry, 10);

    manager.acquireUnit(policy(10), DIGEST);
    manager.acquireUnit(policy(10), DIGEST);
    assertThat(manager.estimatedCachedPermits()).isOne();
    assertThat(manager.leaseDeadlineNanos(policy(10), DIGEST))
        .isEqualTo(Duration.ofMillis(100).toNanos());
    manager.destroy();

    assertThat(registry.get(RateLimiterMetrics.PERMITS_WASTED).counter().count()).isEqualTo(1);
    assertThat(manager.estimatedCachedPermits()).isZero();
  }

  @Test
  void pausedConsumerCannotUseAnExpiredLeaseWhileAnotherContenderRefills() throws Exception {
    List<Integer> batches = Collections.synchronizedList(new ArrayList<>());
    RedisRateLimitBackend backend =
        backend(
            (policy, digest, minimum, desired) -> {
              batches.add(desired);
              return allowed(desired);
            });
    FakeTicker ticker = new FakeTicker();
    AtomicReference<Runnable> validationHook = new AtomicReference<>(() -> {});
    RateLimiterProperties properties = new RateLimiterProperties();
    properties.setCacheMaximumSize(1_000);
    properties.setLockStripes(64);
    properties.setRampUpDuration(Duration.ofSeconds(2));
    LocalLeaseManager manager =
        new LocalLeaseManager(
            backend,
            new RateLimiterMetrics(new SimpleMeterRegistry()),
            properties,
            ticker,
            () -> validationHook.get().run());
    manager.acquireUnit(policy(10), DIGEST);
    manager.acquireUnit(policy(10), DIGEST);
    assertThat(manager.estimatedCachedPermits()).isOne();

    CountDownLatch pausedInsideAtomicMapping = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    AtomicInteger hookCalls = new AtomicInteger();
    AtomicLong observedAfterResume = new AtomicLong(-1);
    validationHook.set(
        () -> {
          if (hookCalls.getAndIncrement() == 0) {
            pausedInsideAtomicMapping.countDown();
            try {
              resume.await();
              observedAfterResume.set(ticker.read());
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(interrupted);
            }
          }
        });

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<LeaseAcquisition> oldConsumer =
          executor.submit(() -> manager.acquireUnit(policy(10), DIGEST));
      assertThat(pausedInsideAtomicMapping.await(5, TimeUnit.SECONDS)).isTrue();
      ticker.advance(Duration.ofMillis(101));
      Future<LeaseAcquisition> replacementContender =
          executor.submit(() -> manager.acquireUnit(policy(10), DIGEST));
      assertThat(replacementContender.isDone()).isFalse();
      resume.countDown();

      LeaseAcquisition oldResult = oldConsumer.get(5, TimeUnit.SECONDS);
      LeaseAcquisition replacementResult = replacementContender.get(5, TimeUnit.SECONDS);
      assertThat(observedAfterResume).hasValue(Duration.ofMillis(101).toNanos());
      assertThat(batches).containsExactly(1, 2, 1, 2);
      assertThat(oldResult.source()).isEqualTo(dev.ratelimiter.core.DecisionSource.REDIS);
      assertThat(replacementResult.source()).isEqualTo(dev.ratelimiter.core.DecisionSource.REDIS);
    } finally {
      resume.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void monotonicDeadlinesSupportNegativeOriginsAndSignedWraparound() {
    RedisRateLimitBackend backend = backend((policy, digest, minimum, desired) -> allowed(desired));
    FakeTicker negativeTicker = new FakeTicker(-TimeUnit.SECONDS.toNanos(1));
    LocalLeaseManager negativeManager =
        manager(backend, negativeTicker, new SimpleMeterRegistry(), 10);
    negativeManager.acquireUnit(policy(10), DIGEST);
    negativeManager.acquireUnit(policy(10), DIGEST);
    assertThat(negativeManager.acquireUnit(policy(10), DIGEST).source())
        .isEqualTo(dev.ratelimiter.core.DecisionSource.LOCAL_LEASE);

    FakeTicker wrappingTicker = new FakeTicker(Long.MAX_VALUE - Duration.ofMillis(50).toNanos());
    LocalLeaseManager wrappingManager =
        manager(backend, wrappingTicker, new SimpleMeterRegistry(), 10);
    wrappingManager.acquireUnit(policy(10), "b".repeat(64));
    wrappingManager.acquireUnit(policy(10), "b".repeat(64));
    assertThat(wrappingManager.leaseDeadlineNanos(policy(10), "b".repeat(64))).isNegative();
    wrappingTicker.advance(Duration.ofMillis(50));
    assertThat(wrappingManager.acquireUnit(policy(10), "b".repeat(64)).source())
        .isEqualTo(dev.ratelimiter.core.DecisionSource.LOCAL_LEASE);
  }

  private static LocalLeaseManager manager(
      RedisRateLimitBackend backend,
      Ticker ticker,
      SimpleMeterRegistry registry,
      int maximumBatch) {
    RateLimiterProperties properties = new RateLimiterProperties();
    properties.setCacheMaximumSize(1_000);
    properties.setLockStripes(64);
    properties.setRampUpDuration(Duration.ofSeconds(2));
    return new LocalLeaseManager(backend, new RateLimiterMetrics(registry), properties, ticker);
  }

  private static TokenBucketPolicy policy(int maximumBatch) {
    return new TokenBucketPolicy(
        "lease-policy",
        1,
        1_000,
        1,
        Duration.ofSeconds(1),
        FailureMode.FAIL_OPEN,
        new LocalCacheSettings(true, maximumBatch, Duration.ofMillis(100), 1, maximumBatch - 1L));
  }

  private static BackendDecision allowed(int desired) {
    return new BackendDecision(true, desired, 1_000, 0, 1_000, 1_000, 1_700_000_000_000L);
  }

  private static RedisRateLimitBackend backend(Reservation reservation) {
    RateLimiterMetrics metrics = new RateLimiterMetrics(new SimpleMeterRegistry());
    return new RedisRateLimitBackend(null, null, null, new RedisAvailabilityClassifier(), metrics) {
      @Override
      public BackendDecision reserve(
          dev.ratelimiter.core.RateLimitPolicy policy,
          String subjectDigest,
          int minimumPermits,
          int desiredPermits) {
        return reservation.reserve(policy, subjectDigest, minimumPermits, desiredPermits);
      }
    };
  }

  @FunctionalInterface
  private interface Reservation {
    BackendDecision reserve(
        dev.ratelimiter.core.RateLimitPolicy policy,
        String subjectDigest,
        int minimumPermits,
        int desiredPermits);
  }

  private static final String DIGEST = "a".repeat(64);

  private static final class FakeTicker implements Ticker {
    private final AtomicLong nanos;

    private FakeTicker() {
      this(0);
    }

    private FakeTicker(long initialNanos) {
      nanos = new AtomicLong(initialNanos);
    }

    @Override
    public long read() {
      return nanos.get();
    }

    void advance(Duration duration) {
      nanos.addAndGet(duration.toNanos());
    }
  }
}
