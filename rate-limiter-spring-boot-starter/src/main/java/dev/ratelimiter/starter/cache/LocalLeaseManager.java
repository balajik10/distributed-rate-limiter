package dev.ratelimiter.starter.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.ratelimiter.core.DecisionSource;
import dev.ratelimiter.core.RateLimitPolicy;
import dev.ratelimiter.starter.backend.BackendDecision;
import dev.ratelimiter.starter.backend.RedisRateLimitBackend;
import dev.ratelimiter.starter.config.RateLimiterProperties;
import dev.ratelimiter.starter.metrics.RateLimiterMetrics;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.DisposableBean;

public final class LocalLeaseManager implements DisposableBean {
  private record RampState(int batch, long reservationNanos, long leaseDeadlineNanos) {}

  private record ConsumedLease(
      LocalPermitLease lease, int remaining, long centralRemaining, long resetAtEpochMillis) {}

  private final RedisRateLimitBackend backend;
  private final RateLimiterMetrics metrics;
  private final Ticker ticker;
  private final Runnable beforeLeaseValidation;
  private final long rampUpNanos;
  private final Cache<LeaseKey, LocalPermitLease> leases;
  private final Cache<LeaseKey, RampState> rampStates;
  private final ReentrantLock[] refillLocks;

  public LocalLeaseManager(
      RedisRateLimitBackend backend, RateLimiterMetrics metrics, RateLimiterProperties properties) {
    this(backend, metrics, properties, Ticker.systemTicker());
  }

  public LocalLeaseManager(
      RedisRateLimitBackend backend,
      RateLimiterMetrics metrics,
      RateLimiterProperties properties,
      Ticker ticker) {
    this(backend, metrics, properties, ticker, () -> {});
  }

  LocalLeaseManager(
      RedisRateLimitBackend backend,
      RateLimiterMetrics metrics,
      RateLimiterProperties properties,
      Ticker ticker,
      Runnable beforeLeaseValidation) {
    this.backend = Objects.requireNonNull(backend, "backend");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.ticker = Objects.requireNonNull(ticker, "ticker");
    this.beforeLeaseValidation =
        Objects.requireNonNull(beforeLeaseValidation, "beforeLeaseValidation");
    if (properties.getCacheMaximumSize() < 1) {
      throw new IllegalArgumentException("rate-limiter.cache-maximum-size must be positive");
    }
    Duration ramp = Objects.requireNonNull(properties.getRampUpDuration(), "ramp-up-duration");
    if (ramp.isZero() || ramp.isNegative()) {
      throw new IllegalArgumentException("rate-limiter.ramp-up-duration must be positive");
    }
    this.rampUpNanos = saturatedNanos(ramp);
    int stripes = properties.getLockStripes();
    if (stripes < 1 || stripes > 65_536 || Integer.bitCount(stripes) != 1) {
      throw new IllegalArgumentException(
          "rate-limiter.lock-stripes must be a power of two up to 65536");
    }
    this.refillLocks = new ReentrantLock[stripes];
    for (int index = 0; index < stripes; index++) {
      refillLocks[index] = new ReentrantLock();
    }

    this.rampStates =
        Caffeine.newBuilder()
            .maximumSize(properties.getCacheMaximumSize())
            .ticker(ticker)
            .expireAfterAccess(ramp)
            .executor(Runnable::run)
            .build();
    this.leases =
        Caffeine.newBuilder()
            .maximumSize(properties.getCacheMaximumSize())
            .ticker(ticker)
            .recordStats()
            .executor(Runnable::run)
            .expireAfter(
                new Expiry<LeaseKey, LocalPermitLease>() {
                  @Override
                  public long expireAfterCreate(
                      LeaseKey key, LocalPermitLease value, long currentTime) {
                    return remainingLifetime(value, currentTime);
                  }

                  @Override
                  public long expireAfterUpdate(
                      LeaseKey key,
                      LocalPermitLease value,
                      long currentTime,
                      long currentDuration) {
                    return remainingLifetime(value, currentTime);
                  }

                  @Override
                  public long expireAfterRead(
                      LeaseKey key,
                      LocalPermitLease value,
                      long currentTime,
                      long currentDuration) {
                    return currentDuration;
                  }
                })
            .evictionListener(this::leaseRemoved)
            .build();
    metrics.monitorCache(leases, "ratelimiter.local.cache.native");
  }

  public LeaseAcquisition acquireUnit(RateLimitPolicy policy, String subjectDigest) {
    LeaseKey key = new LeaseKey(policy.id(), policy.version(), policy.algorithm(), subjectDigest);
    LeaseAcquisition cached = tryConsume(key, policy);
    if (cached != null) {
      return cached;
    }
    metrics.cacheMiss(policy);

    ReentrantLock lock = refillLocks[spread(key.hashCode()) & (refillLocks.length - 1)];
    lock.lock();
    try {
      cached = tryConsume(key, policy);
      if (cached != null) {
        return cached;
      }

      int desiredBatch = nextBatch(key, policy.localCacheSettings().maxLeaseSize(), ticker.read());
      long callStartNanos = ticker.read();
      BackendDecision decision = backend.reserve(policy, subjectDigest, 1, desiredBatch);
      long resetAt = safeAdd(decision.effectiveNowEpochMillis(), decision.resetAfterMillis());
      if (!decision.allowed()) {
        rampStates.invalidate(key);
        return new LeaseAcquisition(
            decision, DecisionSource.REDIS, decision.centralRemaining(), resetAt, false);
      }

      int tail = decision.reservedPermits() - 1;
      long authorizedNanos =
          TimeUnit.MILLISECONDS.toNanos(
              Math.min(
                  policy.localCacheSettings().maxLeaseTtl().toMillis(),
                  decision.reservationValidForMillis()));
      long deadline = callStartNanos + authorizedNanos;
      long now = ticker.read();
      boolean authorizationFresh = isBeforeDeadline(now, deadline);
      boolean cachedTail = tail > 0 && authorizationFresh;
      if (cachedTail) {
        LocalPermitLease lease =
            new LocalPermitLease(policy, tail, deadline, decision.centralRemaining(), resetAt);
        leases.put(key, lease);
        metrics.reservation(policy, tail);
      }
      if (authorizationFresh) {
        rampStates.put(key, new RampState(decision.reservedPermits(), callStartNanos, deadline));
      } else {
        rampStates.invalidate(key);
      }
      long remaining =
          Math.min(policy.limit(), safeAdd(decision.centralRemaining(), cachedTail ? tail : 0));
      return new LeaseAcquisition(decision, DecisionSource.REDIS, remaining, resetAt, cachedTail);
    } finally {
      lock.unlock();
    }
  }

  public long estimatedCachedPermits() {
    return leases.asMap().values().stream().mapToLong(LocalPermitLease::remainingPermits).sum();
  }

  long leaseDeadlineNanos(RateLimitPolicy policy, String subjectDigest) {
    LocalPermitLease lease =
        leases.getIfPresent(
            new LeaseKey(policy.id(), policy.version(), policy.algorithm(), subjectDigest));
    return lease == null ? -1 : lease.deadlineNanos();
  }

  private LeaseAcquisition tryConsume(LeaseKey key, RateLimitPolicy policy) {
    AtomicReference<ConsumedLease> consumed = new AtomicReference<>();
    leases
        .asMap()
        .computeIfPresent(
            key,
            (ignored, current) -> {
              beforeLeaseValidation.run();
              long now = ticker.read();
              if (!isBeforeDeadline(now, current.deadlineNanos())) {
                retireRampState(key, current.deadlineNanos());
                metrics.wasted(current.policy(), current.retire(), false);
                return null;
              }
              if (current.remainingPermits() == 0) {
                return null;
              }
              int remaining = current.consumeOne();
              consumed.set(
                  new ConsumedLease(
                      current,
                      remaining,
                      current.centralRemainingAtReservation(),
                      current.resetAtEpochMillis()));
              return current;
            });
    ConsumedLease result = consumed.get();
    if (result == null) {
      return null;
    }
    metrics.cacheHit(policy);
    if (result.remaining() == 0) {
      leases.asMap().remove(key, result.lease());
    }
    long remaining =
        Math.min(policy.limit(), safeAdd(result.centralRemaining(), result.remaining()));
    return new LeaseAcquisition(
        null, DecisionSource.LOCAL_LEASE, remaining, result.resetAtEpochMillis(), true);
  }

  private int nextBatch(LeaseKey key, int maximum, long now) {
    RampState previous = rampStates.getIfPresent(key);
    if (previous == null
        || !isBeforeDeadline(now, previous.leaseDeadlineNanos())
        || now - previous.reservationNanos() > rampUpNanos) {
      return 1;
    }
    return (int) Math.min(maximum, Math.min((long) maximum, (long) previous.batch() * 2));
  }

  private void leaseRemoved(LeaseKey key, LocalPermitLease lease, RemovalCause cause) {
    if (key == null || lease == null) {
      return;
    }
    metrics.wasted(lease.policy(), lease.remainingPermits(), cause.wasEvicted());
    if (cause.wasEvicted() || cause == RemovalCause.EXPIRED) {
      retireRampState(key, lease.deadlineNanos());
    }
  }

  private void retireRampState(LeaseKey key, long retiredDeadlineNanos) {
    rampStates
        .asMap()
        .computeIfPresent(
            key,
            (ignored, state) -> state.leaseDeadlineNanos() == retiredDeadlineNanos ? null : state);
  }

  private static long remainingLifetime(LocalPermitLease lease, long currentTime) {
    long remaining = lease.deadlineNanos() - currentTime;
    return remaining > 0 ? remaining : 1;
  }

  private static int spread(int hash) {
    return hash ^ (hash >>> 16);
  }

  private static long saturatedNanos(Duration duration) {
    try {
      return duration.toNanos();
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException("rate-limiter.ramp-up-duration is too large", overflow);
    }
  }

  private static boolean isBeforeDeadline(long now, long deadline) {
    return deadline - now > 0;
  }

  private static long safeAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException("Rate-limit timestamp overflow", overflow);
    }
  }

  @Override
  public void destroy() {
    leases
        .asMap()
        .forEach(
            (key, lease) -> {
              metrics.wasted(lease.policy(), lease.retire(), false);
              retireRampState(key, lease.deadlineNanos());
            });
    leases.invalidateAll();
    leases.cleanUp();
    rampStates.invalidateAll();
  }
}
