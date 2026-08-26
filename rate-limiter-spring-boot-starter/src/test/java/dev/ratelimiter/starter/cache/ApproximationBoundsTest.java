package dev.ratelimiter.starter.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.ratelimiter.core.DecisionSource;
import dev.ratelimiter.core.FailureMode;
import dev.ratelimiter.core.LocalCacheSettings;
import dev.ratelimiter.core.RateLimitPolicy;
import dev.ratelimiter.core.TokenBucketPolicy;
import dev.ratelimiter.starter.backend.BackendDecision;
import dev.ratelimiter.starter.backend.BackendUnavailableException;
import dev.ratelimiter.starter.backend.RedisAvailabilityClassifier;
import dev.ratelimiter.starter.backend.RedisRateLimitBackend;
import dev.ratelimiter.starter.config.RateLimiterProperties;
import dev.ratelimiter.starter.metrics.RateLimiterMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ApproximationBoundsTest {
  private static final String DIGEST = "a".repeat(64);
  private static final long INTERVAL_MODEL_SEED = 0x1A7E5EEDL;

  @Test
  void fixedSeedChargeAheadExecutionNeverExceedsMtimesBMinusOne() {
    int instances = 13;
    int maximumBatch = 8;
    int capacity = 5_000;
    long plannedBound = (long) instances * (maximumBatch - 1);
    LocalCacheSettings settings =
        new LocalCacheSettings(true, maximumBatch, Duration.ofSeconds(1), instances, plannedBound);
    TokenBucketPolicy policy = tokenPolicy(capacity, settings);
    CentralModelBackend backend = new CentralModelBackend(capacity);
    FakeTicker ticker = new FakeTicker();
    List<LocalLeaseManager> managers = managers(instances, backend, ticker);
    Random random = new Random(0x5EEDC0DEL);
    long admitted = 0;
    long maximumInvisible = 0;

    for (int request = 0; request < 12_000; request++) {
      LocalLeaseManager manager = managers.get(random.nextInt(managers.size()));
      if (manager.acquireUnit(policy, DIGEST).allowed()) {
        admitted++;
      }
      long cached = managers.stream().mapToLong(LocalLeaseManager::estimatedCachedPermits).sum();
      long invisible = backend.charged() - admitted;
      maximumInvisible = Math.max(maximumInvisible, invisible);
      assertThat(invisible).isEqualTo(cached).isBetween(0L, plannedBound);
    }

    for (LocalLeaseManager manager : managers) {
      while (manager.estimatedCachedPermits() > 0) {
        assertThat(manager.acquireUnit(policy, DIGEST).allowed()).isTrue();
        admitted++;
      }
    }

    assertThat(settings.plannedTimingErrorPermits()).isEqualTo(plannedBound);
    assertThat(maximumInvisible).isLessThanOrEqualTo(plannedBound);
    assertThat(backend.charged() - admitted).isZero();
    assertThat(admitted).isEqualTo(capacity);
    managers.forEach(LocalLeaseManager::destroy);
  }

  @Test
  void fixedSeedIntervalBoundaryTimingShiftNeverExceedsMtimesBMinusOne() {
    int instances = 9;
    int maximumBatch = 8;
    int intervalRequests = 96;
    int intervalCount = 60;
    long intervalNanos = Duration.ofMillis(100).toNanos();
    long plannedBound = (long) instances * (maximumBatch - 1);
    LocalCacheSettings settings =
        new LocalCacheSettings(true, maximumBatch, Duration.ofMillis(250), instances, plannedBound);
    TokenBucketPolicy policy = tokenPolicy(1_000, settings);
    FakeTicker ticker = new FakeTicker();
    IntervalModelBackend backend =
        new IntervalModelBackend(1_000, Duration.ofNanos(intervalNanos), ticker);
    List<LocalLeaseManager> managers = managers(instances, backend, ticker);
    Random random = new Random(INTERVAL_MODEL_SEED);
    long maximumAbsoluteShift = 0;

    for (int interval = 0; interval < intervalCount; interval++) {
      long boundary = interval * intervalNanos;
      ticker.advanceTo(boundary);
      backend.synchronizeInterval();
      long carryAtBoundary =
          managers.stream().mapToLong(LocalLeaseManager::estimatedCachedPermits).sum();
      assertThat(carryAtBoundary)
          .as("seed=0x%X interval=%d carry must fit M(B-1)", INTERVAL_MODEL_SEED, interval)
          .isBetween(0L, plannedBound);

      List<Long> offsets = new ArrayList<>();
      for (int request = 0; request < intervalRequests; request++) {
        offsets.add(random.nextLong(intervalNanos));
      }
      Collections.sort(offsets);
      long admittedThisInterval = 0;
      for (int request = 0; request < offsets.size(); request++) {
        ticker.advanceTo(boundary + offsets.get(request));
        LocalLeaseManager manager = managers.get(random.nextInt(instances));
        if (manager.acquireUnit(policy, DIGEST).allowed()) {
          admittedThisInterval++;
        }
        long timingShift = admittedThisInterval - backend.chargedInCurrentInterval();
        maximumAbsoluteShift = Math.max(maximumAbsoluteShift, Math.abs(timingShift));
        assertThat(timingShift)
            .as(
                "seed=0x%X interval=%d request=%d carry=%d charged=%d admitted=%d",
                INTERVAL_MODEL_SEED,
                interval,
                request,
                carryAtBoundary,
                backend.chargedInCurrentInterval(),
                admittedThisInterval)
            .isBetween(-plannedBound, plannedBound)
            .isLessThanOrEqualTo(carryAtBoundary);
      }
    }

    assertThat(maximumAbsoluteShift)
        .as("seed=0x%X must exercise a nonzero boundary shift", INTERVAL_MODEL_SEED)
        .isPositive()
        .isLessThanOrEqualTo(plannedBound);
    managers.forEach(LocalLeaseManager::destroy);
  }

  @Test
  void losingAllWarmNodesDoesNotRefundAndLosesExactlyThePlannedMaximum() {
    int instances = 11;
    int maximumBatch = 8;
    int capacity = 1_000;
    long plannedBound = (long) instances * (maximumBatch - 1);
    LocalCacheSettings settings =
        new LocalCacheSettings(true, maximumBatch, Duration.ofSeconds(1), instances, plannedBound);
    TokenBucketPolicy policy = tokenPolicy(capacity, settings);
    CentralModelBackend backend = new CentralModelBackend(capacity);
    List<LocalLeaseManager> managers = managers(instances, backend, new FakeTicker());
    long admitted = 0;

    for (LocalLeaseManager manager : managers) {
      for (int request = 0; request < 8; request++) {
        assertThat(manager.acquireUnit(policy, DIGEST).allowed()).isTrue();
        admitted++;
      }
      assertThat(manager.estimatedCachedPermits()).isEqualTo(maximumBatch - 1L);
    }
    assertThat(backend.charged() - admitted).isEqualTo(plannedBound);

    managers.forEach(LocalLeaseManager::destroy);

    assertThat(managers.stream().mapToLong(LocalLeaseManager::estimatedCachedPermits).sum())
        .isZero();
    assertThat(backend.charged() - admitted).isEqualTo(plannedBound);
    assertThat(backend.remaining()).isEqualTo(capacity - backend.charged());
  }

  @Test
  void cachedTailRemainsUsableDuringOutageButExhaustionRequiresRedis() {
    AtomicBoolean outage = new AtomicBoolean();
    CentralModelBackend backend = new CentralModelBackend(100, outage);
    LocalCacheSettings settings = new LocalCacheSettings(true, 8, Duration.ofSeconds(1), 1, 7);
    TokenBucketPolicy policy = tokenPolicy(100, settings);
    LocalLeaseManager manager = managers(1, backend, new FakeTicker()).getFirst();

    assertThat(manager.acquireUnit(policy, DIGEST).source()).isEqualTo(DecisionSource.REDIS);
    assertThat(manager.acquireUnit(policy, DIGEST).source()).isEqualTo(DecisionSource.REDIS);
    assertThat(manager.estimatedCachedPermits()).isOne();
    outage.set(true);

    assertThat(manager.acquireUnit(policy, DIGEST).source()).isEqualTo(DecisionSource.LOCAL_LEASE);
    assertThat(manager.estimatedCachedPermits()).isZero();
    assertThatThrownBy(() -> manager.acquireUnit(policy, DIGEST))
        .isInstanceOf(BackendUnavailableException.class);
    assertThat(backend.charged()).isEqualTo(3);
    manager.destroy();
  }

  @Test
  void fixedSeedCounterReferencePreservesWeightedAndNearlyTwoLimitBounds() {
    int limit = 73;
    long window = 60_000;
    CounterReference counter = new CounterReference(limit, window);

    assertThat(counter.reserve(limit)).isTrue();
    counter.advanceTo(window);
    counter.advanceTo(2 * window - 1);
    assertThat(counter.available()).isEqualTo(limit - 1);
    assertThat(counter.reserve(limit - 1)).isTrue();
    assertThat(counter.rawStoredCount()).isEqualTo(2L * limit - 1);

    Random random = new Random(0xC0FFEE42L);
    long maximumStored = counter.rawStoredCount();
    for (int request = 0; request < 100_000; request++) {
      counter.advanceBy(random.nextLong(2 * window + 1));
      counter.reserve(1 + random.nextInt(20));
      maximumStored = Math.max(maximumStored, counter.rawStoredCount());
      assertThat(counter.weightedNumerator()).isBetween(0L, limit * window);
      assertThat(counter.currentCount()).isBetween(0L, (long) limit);
      assertThat(counter.previousCount()).isBetween(0L, (long) limit);
      assertThat(counter.rawStoredCount()).isLessThanOrEqualTo(2L * limit - 1);
    }

    assertThat(maximumStored).isEqualTo(2L * limit - 1);
  }

  private static List<LocalLeaseManager> managers(
      int count, RedisRateLimitBackend backend, Ticker ticker) {
    RateLimiterProperties properties = new RateLimiterProperties();
    properties.setCacheMaximumSize(10_000);
    properties.setLockStripes(64);
    properties.setRampUpDuration(Duration.ofMinutes(1));
    List<LocalLeaseManager> managers = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      managers.add(
          new LocalLeaseManager(
              backend, new RateLimiterMetrics(new SimpleMeterRegistry()), properties, ticker));
    }
    return managers;
  }

  private static TokenBucketPolicy tokenPolicy(int capacity, LocalCacheSettings settings) {
    return new TokenBucketPolicy(
        "approximation",
        1,
        capacity,
        capacity,
        Duration.ofSeconds(1),
        FailureMode.FAIL_CLOSED,
        settings);
  }

  private static final class CentralModelBackend extends RedisRateLimitBackend {
    private final AtomicInteger remaining;
    private final AtomicInteger charged = new AtomicInteger();
    private final AtomicBoolean outage;

    CentralModelBackend(int capacity) {
      this(capacity, new AtomicBoolean());
    }

    CentralModelBackend(int capacity, AtomicBoolean outage) {
      super(
          null,
          null,
          null,
          new RedisAvailabilityClassifier(),
          new RateLimiterMetrics(new SimpleMeterRegistry()));
      this.remaining = new AtomicInteger(capacity);
      this.outage = outage;
    }

    @Override
    public BackendDecision reserve(
        RateLimitPolicy policy, String subjectDigest, int minimumPermits, int desiredPermits) {
      if (outage.get()) {
        throw new BackendUnavailableException(
            BackendUnavailableException.Category.CONNECTION,
            new IllegalStateException("model outage"));
      }
      while (true) {
        int before = remaining.get();
        if (before < minimumPermits) {
          return new BackendDecision(false, 0, before, 1, 1_000, 0, 1_700_000_000_000L);
        }
        int reserved = Math.min(before, desiredPermits);
        if (remaining.compareAndSet(before, before - reserved)) {
          charged.addAndGet(reserved);
          return new BackendDecision(
              true, reserved, before - reserved, 0, 1_000, 1_000, 1_700_000_000_000L);
        }
      }
    }

    int charged() {
      return charged.get();
    }

    int remaining() {
      return remaining.get();
    }
  }

  private static final class FakeTicker implements Ticker {
    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long read() {
      return nanos.get();
    }

    void advanceTo(long targetNanos) {
      long current = nanos.get();
      if (targetNanos < current) {
        throw new IllegalArgumentException("ticker must advance monotonically");
      }
      nanos.set(targetNanos);
    }
  }

  private static final class IntervalModelBackend extends RedisRateLimitBackend {
    private final int capacity;
    private final long intervalNanos;
    private final FakeTicker ticker;
    private long currentInterval = -1;
    private int remaining;
    private int chargedInCurrentInterval;

    IntervalModelBackend(int capacity, Duration interval, FakeTicker ticker) {
      super(
          null,
          null,
          null,
          new RedisAvailabilityClassifier(),
          new RateLimiterMetrics(new SimpleMeterRegistry()));
      this.capacity = capacity;
      this.intervalNanos = interval.toNanos();
      this.ticker = ticker;
    }

    @Override
    public synchronized BackendDecision reserve(
        RateLimitPolicy policy, String subjectDigest, int minimumPermits, int desiredPermits) {
      synchronizeInterval();
      if (remaining < minimumPermits) {
        return new BackendDecision(false, 0, remaining, 1, 100, 0, effectiveEpochMillis());
      }
      int reserved = Math.min(remaining, desiredPermits);
      remaining -= reserved;
      chargedInCurrentInterval += reserved;
      return new BackendDecision(true, reserved, remaining, 0, 100, 250, effectiveEpochMillis());
    }

    synchronized void synchronizeInterval() {
      long interval = Math.floorDiv(ticker.read(), intervalNanos);
      if (interval != currentInterval) {
        currentInterval = interval;
        remaining = capacity;
        chargedInCurrentInterval = 0;
      }
    }

    synchronized int chargedInCurrentInterval() {
      synchronizeInterval();
      return chargedInCurrentInterval;
    }

    private long effectiveEpochMillis() {
      return 1_700_000_000_000L + TimeUnit.NANOSECONDS.toMillis(ticker.read());
    }
  }

  private static final class CounterReference {
    private final int limit;
    private final long window;
    private long now;
    private long currentStart;
    private long currentCount;
    private long previousCount;

    CounterReference(int limit, long window) {
      this.limit = limit;
      this.window = window;
    }

    void advanceBy(long millis) {
      advanceTo(Math.addExact(now, millis));
    }

    void advanceTo(long epochMillis) {
      if (epochMillis < now) {
        throw new IllegalArgumentException("reference time must be monotonic");
      }
      now = epochMillis;
      long bucketStart = Math.floorDiv(now, window) * window;
      if (bucketStart > currentStart) {
        long bucketsElapsed = (bucketStart - currentStart) / window;
        previousCount = bucketsElapsed == 1 ? currentCount : 0;
        currentCount = 0;
        currentStart = bucketStart;
      }
    }

    boolean reserve(int permits) {
      if (available() < permits) {
        return false;
      }
      currentCount += permits;
      return true;
    }

    long available() {
      return Math.max(0, Math.min(limit, (limit * window - weightedNumerator()) / window));
    }

    long weightedNumerator() {
      long elapsed = now - currentStart;
      return previousCount * (window - elapsed) + currentCount * window;
    }

    long rawStoredCount() {
      return currentCount + previousCount;
    }

    long currentCount() {
      return currentCount;
    }

    long previousCount() {
      return previousCount;
    }
  }
}
