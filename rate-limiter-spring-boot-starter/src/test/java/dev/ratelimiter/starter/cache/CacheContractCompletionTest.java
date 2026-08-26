package dev.ratelimiter.starter.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.ratelimiter.core.DecisionReason;
import dev.ratelimiter.core.DecisionSource;
import dev.ratelimiter.core.FailureMode;
import dev.ratelimiter.core.LocalCacheSettings;
import dev.ratelimiter.core.PolicyProvider;
import dev.ratelimiter.core.RateLimitPolicy;
import dev.ratelimiter.core.RateLimitRequest;
import dev.ratelimiter.core.TokenBucketPolicy;
import dev.ratelimiter.starter.DefaultDistributedRateLimiter;
import dev.ratelimiter.starter.backend.BackendDecision;
import dev.ratelimiter.starter.backend.BackendUnavailableException;
import dev.ratelimiter.starter.backend.RedisAvailabilityClassifier;
import dev.ratelimiter.starter.backend.RedisRateLimitBackend;
import dev.ratelimiter.starter.config.RateLimiterProperties;
import dev.ratelimiter.starter.hash.KeyHasher;
import dev.ratelimiter.starter.metrics.RateLimiterMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

class CacheContractCompletionTest {
  private static final String POLICY_ID = "cache-contract";
  private static final String DIGEST_A = "a".repeat(64);
  private static final String DIGEST_B = "b".repeat(64);

  @Test
  void sizeEvictionCountsTheChargedTailAsWasteAndIncrementsEvictionMetrics() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RecordingBackend backend = new RecordingBackend();
    MutableTicker ticker = new MutableTicker();
    LocalLeaseManager manager = manager(backend, registry, ticker, 1);
    TokenBucketPolicy policy = leasedPolicy(1, 8, Duration.ofMillis(100));

    assertThat(manager.acquireUnit(policy, DIGEST_A).allowed()).isTrue();
    assertThat(manager.acquireUnit(policy, DIGEST_A).allowed()).isTrue();
    assertThat(manager.estimatedCachedPermits()).isOne();
    assertThat(manager.acquireUnit(policy, DIGEST_B).allowed()).isTrue();
    assertThat(manager.acquireUnit(policy, DIGEST_B).allowed()).isTrue();

    assertThat(backend.desiredBatches()).containsExactly(1, 2, 1, 2);
    assertThat(backend.charged()).isEqualTo(6);
    assertThat(manager.estimatedCachedPermits()).isOne();
    assertThat(counter(registry, RateLimiterMetrics.PERMITS_WASTED)).isEqualTo(1);
    assertThat(counter(registry, RateLimiterMetrics.CACHE_EVICTIONS)).isEqualTo(1);
    assertThat(gauge(registry, RateLimiterMetrics.CURRENT_CACHED_TAIL)).isEqualTo(1);

    manager.destroy();
    registry.close();
  }

  @Test
  void leaseExpiryCountsUnusedChargedPermitsAsWasteAndRestartsBatchRamp() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RecordingBackend backend = new RecordingBackend();
    MutableTicker ticker = new MutableTicker();
    LocalLeaseManager manager = manager(backend, registry, ticker, 16);
    TokenBucketPolicy policy = leasedPolicy(1, 8, Duration.ofMillis(100));

    manager.acquireUnit(policy, DIGEST_A);
    manager.acquireUnit(policy, DIGEST_A);
    assertThat(manager.estimatedCachedPermits()).isOne();
    ticker.advance(Duration.ofMillis(101));

    assertThat(manager.acquireUnit(policy, DIGEST_A).source()).isEqualTo(DecisionSource.REDIS);
    assertThat(backend.desiredBatches()).containsExactly(1, 2, 1);
    assertThat(manager.estimatedCachedPermits()).isZero();
    manager.destroy();

    assertThat(counter(registry, RateLimiterMetrics.PERMITS_WASTED)).isEqualTo(1);
    assertThat(gauge(registry, RateLimiterMetrics.CURRENT_CACHED_TAIL)).isZero();
    registry.close();
  }

  @Test
  void policyVersionChangeCannotConsumeOldConfigurationTailAndNodeLossNeverRefundsIt() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RecordingBackend backend = new RecordingBackend();
    LocalLeaseManager manager = manager(backend, registry, new MutableTicker(), 16);
    TokenBucketPolicy versionOne = leasedPolicy(1, 8, Duration.ofMillis(100));
    TokenBucketPolicy versionTwo = leasedPolicy(2, 4, Duration.ofMillis(100));

    assertThat(manager.acquireUnit(versionOne, DIGEST_A).source()).isEqualTo(DecisionSource.REDIS);
    assertThat(manager.acquireUnit(versionOne, DIGEST_A).source()).isEqualTo(DecisionSource.REDIS);
    assertThat(manager.estimatedCachedPermits()).isOne();
    assertThat(manager.acquireUnit(versionTwo, DIGEST_A).source()).isEqualTo(DecisionSource.REDIS);

    assertThat(backend.policyVersions()).containsExactly(1L, 1L, 2L);
    assertThat(backend.desiredBatches()).containsExactly(1, 2, 1);
    assertThat(backend.charged()).isEqualTo(4);
    assertThat(manager.estimatedCachedPermits()).isOne();

    manager.destroy();

    assertThat(manager.estimatedCachedPermits()).isZero();
    assertThat(backend.charged()).isEqualTo(4);
    assertThat(counter(registry, RateLimiterMetrics.PERMITS_WASTED)).isEqualTo(1);
    registry.close();
  }

  @Test
  void disabledCacheAndEnabledBatchOneCallRedisForEveryRepeatedRequest() {
    int repetitions = 40;
    LocalCacheSettings[] strictSettings = {
      LocalCacheSettings.disabled(), new LocalCacheSettings(true, 1, Duration.ofMillis(100), 1, 0)
    };

    for (LocalCacheSettings settings : strictSettings) {
      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      RecordingBackend backend = new RecordingBackend();
      TokenBucketPolicy policy = policy(1, FailureMode.FAIL_CLOSED, settings);
      DefaultDistributedRateLimiter limiter = limiter(policy, backend, null, registry);

      for (int request = 0; request < repetitions; request++) {
        var decision = limiter.tryAcquire(new RateLimitRequest(POLICY_ID, "strict-subject", 1));
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.source()).isEqualTo(DecisionSource.REDIS);
        assertThat(decision.approximate()).isFalse();
      }

      assertThat(backend.calls()).isEqualTo(repetitions);
      assertThat(backend.minimumPermits()).containsOnly(1).hasSize(repetitions);
      assertThat(backend.desiredBatches()).containsOnly(1).hasSize(repetitions);
      registry.close();
    }
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void failOpenIsAllowedDegradedMetricizedAndNeverLeaksTheLogicalKey(
      CapturedOutput capturedOutput) {
    String rawLogicalKey = "customer-secret-key-8e0177";
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RecordingBackend backend = RecordingBackend.unavailable(rawLogicalKey);
    TokenBucketPolicy policy = policy(1, FailureMode.FAIL_OPEN, LocalCacheSettings.disabled());
    DefaultDistributedRateLimiter limiter = limiter(policy, backend, null, registry);

    var decision = limiter.tryAcquire(new RateLimitRequest(POLICY_ID, rawLogicalKey, 3));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.grantedPermits()).isEqualTo(3);
    assertThat(decision.source()).isEqualTo(DecisionSource.FAIL_OPEN);
    assertThat(decision.reason()).isEqualTo(DecisionReason.BACKEND_UNAVAILABLE_FAIL_OPEN);
    assertThat(decision.degraded()).isTrue();
    assertThat(decision.approximate()).isTrue();
    assertThat(
            registry
                .get(RateLimiterMetrics.FALLBACK_ACTIVATIONS)
                .tags(
                    "policy",
                    POLICY_ID,
                    "algorithm",
                    "TOKEN_BUCKET",
                    "source",
                    "FAIL_OPEN",
                    "failure_mode",
                    "FAIL_OPEN")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get(RateLimiterMetrics.DECISIONS)
                .tags(
                    "policy",
                    POLICY_ID,
                    "algorithm",
                    "TOKEN_BUCKET",
                    "outcome",
                    "allowed",
                    "source",
                    "FAIL_OPEN",
                    "failure_mode",
                    "FAIL_OPEN")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(registry.getMeters())
        .allSatisfy(meter -> assertThat(meter.getId().toString()).doesNotContain(rawLogicalKey));
    assertThat(capturedOutput.getAll()).doesNotContain(rawLogicalKey);
    registry.close();
  }

  private static LocalLeaseManager manager(
      RedisRateLimitBackend backend,
      SimpleMeterRegistry registry,
      Ticker ticker,
      long maximumSize) {
    RateLimiterProperties properties = new RateLimiterProperties();
    properties.setCacheMaximumSize(maximumSize);
    properties.setLockStripes(16);
    properties.setRampUpDuration(Duration.ofMinutes(1));
    return new LocalLeaseManager(backend, new RateLimiterMetrics(registry), properties, ticker);
  }

  private static DefaultDistributedRateLimiter limiter(
      TokenBucketPolicy policy,
      RedisRateLimitBackend backend,
      LocalLeaseManager leases,
      SimpleMeterRegistry registry) {
    PolicyProvider policies = id -> Optional.ofNullable(POLICY_ID.equals(id) ? policy : null);
    return new DefaultDistributedRateLimiter(
        policies, new KeyHasher(null), backend, leases, new RateLimiterMetrics(registry));
  }

  private static TokenBucketPolicy leasedPolicy(long version, int maximumBatch, Duration leaseTtl) {
    return policy(
        version,
        FailureMode.FAIL_CLOSED,
        new LocalCacheSettings(true, maximumBatch, leaseTtl, 1, maximumBatch - 1L));
  }

  private static TokenBucketPolicy policy(
      long version, FailureMode failureMode, LocalCacheSettings settings) {
    return new TokenBucketPolicy(
        POLICY_ID, version, 100, 100, Duration.ofSeconds(1), failureMode, settings);
  }

  private static double counter(SimpleMeterRegistry registry, String name) {
    return registry.get(name).counter().count();
  }

  private static double gauge(SimpleMeterRegistry registry, String name) {
    return registry.get(name).gauge().value();
  }

  private record Reservation(long policyVersion, int minimumPermits, int desiredPermits) {}

  private static final class RecordingBackend extends RedisRateLimitBackend {
    private final List<Reservation> reservations = new ArrayList<>();
    private final AtomicInteger charged = new AtomicInteger();
    private final String unavailableDetail;

    RecordingBackend() {
      this(null);
    }

    private RecordingBackend(String unavailableDetail) {
      super(
          null,
          null,
          null,
          new RedisAvailabilityClassifier(),
          new RateLimiterMetrics(new SimpleMeterRegistry()));
      this.unavailableDetail = unavailableDetail;
    }

    static RecordingBackend unavailable(String detail) {
      return new RecordingBackend(detail);
    }

    @Override
    public BackendDecision reserve(
        RateLimitPolicy policy, String subjectDigest, int minimumPermits, int desiredPermits) {
      reservations.add(new Reservation(policy.version(), minimumPermits, desiredPermits));
      if (unavailableDetail != null) {
        throw new BackendUnavailableException(
            BackendUnavailableException.Category.CONNECTION,
            new IllegalStateException("unavailable for " + unavailableDetail));
      }
      charged.addAndGet(desiredPermits);
      return new BackendDecision(
          true,
          desiredPermits,
          Math.max(0, policy.limit() - desiredPermits),
          0,
          1_000,
          1_000,
          1_700_000_000_000L);
    }

    int calls() {
      return reservations.size();
    }

    int charged() {
      return charged.get();
    }

    List<Long> policyVersions() {
      return reservations.stream().map(Reservation::policyVersion).toList();
    }

    List<Integer> minimumPermits() {
      return reservations.stream().map(Reservation::minimumPermits).toList();
    }

    List<Integer> desiredBatches() {
      return reservations.stream().map(Reservation::desiredPermits).toList();
    }
  }

  private static final class MutableTicker implements Ticker {
    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long read() {
      return nanos.get();
    }

    void advance(Duration duration) {
      nanos.addAndGet(duration.toNanos());
    }
  }
}
