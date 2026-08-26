package dev.ratelimiter.starter.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.ratelimiter.core.Algorithm;
import dev.ratelimiter.core.FailureMode;
import dev.ratelimiter.core.LocalCacheSettings;
import dev.ratelimiter.core.RateLimitPolicy;
import dev.ratelimiter.core.SlidingWindowCounterPolicy;
import dev.ratelimiter.core.SlidingWindowLogPolicy;
import dev.ratelimiter.core.TokenBucketPolicy;
import dev.ratelimiter.starter.hash.RedisKeyFactory;
import dev.ratelimiter.starter.metrics.RateLimiterMetrics;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.RedisClientInfo;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisAlgorithmHardeningIT {
  private static final int INDEPENDENT_CLIENTS = 10;
  private static final int CENTRAL_LIMIT = 100;
  private static final long COUNTER_WINDOW_MILLIS = 60_000;
  private static final long MAX_CONCURRENT_TEST_DURATION_MILLIS = 10_000;
  private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:8.0-alpine");

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);

  private static final List<RedisClient> CLIENTS = new ArrayList<>();
  private static final RedisKeyFactory KEYS = new RedisKeyFactory("hardening");

  @BeforeAll
  static void connectIndependentClients() {
    for (int index = 0; index < INDEPENDENT_CLIENTS; index++) {
      CLIENTS.add(
          RedisClient.connect(
              REDIS.getHost(), REDIS.getMappedPort(6379), "hardening-client-" + index));
    }
  }

  @AfterAll
  static void disconnectIndependentClients() {
    CLIENTS.forEach(RedisClient::close);
    CLIENTS.clear();
  }

  @BeforeEach
  void flushRedis() {
    try (var connection = primary().factory().getConnection()) {
      connection.serverCommands().flushDb();
    }
  }

  @Test
  void exactBoundariesCreateIsolatedStateWithDocumentedShapesAndTtls() {
    String sharedDigest = digest('a');
    String isolatedDigest = digest('b');
    TokenBucketPolicy tokenPolicy = tokenPolicy("token-boundary", 5, 5, Duration.ofSeconds(1));
    SlidingWindowLogPolicy logPolicy = logPolicy("log-boundary", 5, Duration.ofSeconds(2));
    SlidingWindowCounterPolicy counterPolicy =
        counterPolicy("counter-boundary", 5, Duration.ofSeconds(2));

    BackendDecision tokenAllowed = primary().tokenBucket().reserve(tokenPolicy, sharedDigest, 5, 5);
    BackendDecision tokenDenied = primary().tokenBucket().reserve(tokenPolicy, sharedDigest, 1, 1);
    BackendDecision logAllowed = primary().slidingLog().reserve(logPolicy, sharedDigest, 5, 5);
    BackendDecision logDenied = primary().slidingLog().reserve(logPolicy, sharedDigest, 1, 1);
    BackendDecision counterAllowed =
        primary().slidingCounter().reserve(counterPolicy, sharedDigest, 5, 5);
    BackendDecision counterDenied =
        primary().slidingCounter().reserve(counterPolicy, sharedDigest, 1, 1);

    assertThat(tokenAllowed.reservedPermits()).isEqualTo(5);
    assertThat(tokenDenied.allowed()).isFalse();
    assertThat(tokenDenied.retryAfterMillis()).isBetween(1L, 1_000L);
    assertThat(logAllowed.reservedPermits()).isEqualTo(5);
    assertThat(logDenied.allowed()).isFalse();
    assertThat(logDenied.retryAfterMillis()).isBetween(1L, 2_000L);
    assertThat(counterAllowed.reservedPermits()).isEqualTo(5);
    assertThat(counterDenied.allowed()).isFalse();
    assertThat(counterDenied.retryAfterMillis()).isPositive();

    String tokenKey = KEYS.tokenBucket(sharedDigest);
    List<String> logKeys = KEYS.slidingWindowLog(sharedDigest);
    String counterKey = KEYS.slidingWindowCounter(sharedDigest);
    assertThat(primary().redis().type(tokenKey).code()).isEqualTo("hash");
    assertThat(primary().redis().opsForHash().entries(tokenKey))
        .containsOnlyKeys("balance_units", "last_ms");
    assertThat(primary().redis().getExpire(tokenKey, TimeUnit.MILLISECONDS))
        .isBetween(1_000L, 2_100L);
    assertThat(primary().redis().type(logKeys.get(0)).code()).isEqualTo("zset");
    assertThat(primary().redis().opsForZSet().size(logKeys.get(0))).isEqualTo(5);
    assertThat(primary().redis().opsForHash().entries(logKeys.get(1)))
        .containsOnlyKeys("last_ms", "sequence");
    assertThat(primary().redis().getExpire(logKeys.get(0), TimeUnit.MILLISECONDS))
        .isBetween(2_000L, 3_100L);
    assertThat(primary().redis().getExpire(logKeys.get(1), TimeUnit.MILLISECONDS))
        .isBetween(3_000L, 4_100L);
    assertThat(primary().redis().opsForHash().entries(counterKey))
        .containsOnlyKeys("current_start_ms", "current_count", "previous_count", "last_ms");
    assertThat(primary().redis().opsForHash().get(counterKey, "current_count")).isEqualTo("5");
    assertThat(primary().redis().getExpire(counterKey, TimeUnit.MILLISECONDS))
        .isBetween(4_000L, 5_100L);

    assertThat(primary().tokenBucket().reserve(tokenPolicy, isolatedDigest, 5, 5).allowed())
        .isTrue();
    assertThat(primary().slidingLog().reserve(logPolicy, isolatedDigest, 5, 5).allowed()).isTrue();
    assertThat(primary().slidingCounter().reserve(counterPolicy, isolatedDigest, 5, 5).allowed())
        .isTrue();
    assertThat(primary().redis().keys("hardening:*")).hasSize(8);
    assertThat(Set.copyOf(List.of(tokenKey, logKeys.get(0), logKeys.get(1), counterKey)))
        .hasSize(4)
        .allSatisfy(key -> assertThat(key).contains("{" + sharedDigest + "}"));
  }

  @Test
  void wrongTypesForEveryPhysicalKeyFailBeforeAnyMutation() {
    TokenBucketPolicy tokenPolicy = tokenPolicy("wrong-token", 5, 5, Duration.ofSeconds(1));
    SlidingWindowLogPolicy logPolicy = logPolicy("wrong-log", 5, Duration.ofSeconds(10));
    SlidingWindowCounterPolicy counterPolicy =
        counterPolicy("wrong-counter", 5, Duration.ofSeconds(10));

    String tokenKey = KEYS.tokenBucket(digest('c'));
    primary().redis().opsForValue().set(tokenKey, "token-sentinel");
    assertThatThrownBy(() -> primary().tokenBucket().reserve(tokenPolicy, digest('c'), 1, 1))
        .isInstanceOf(RuntimeException.class);
    assertThat(primary().redis().opsForValue().get(tokenKey)).isEqualTo("token-sentinel");
    assertThat(primary().redis().getExpire(tokenKey)).isEqualTo(-1L);

    String counterKey = KEYS.slidingWindowCounter(digest('d'));
    primary().redis().opsForList().rightPush(counterKey, "counter-sentinel");
    assertThatThrownBy(() -> primary().slidingCounter().reserve(counterPolicy, digest('d'), 1, 1))
        .isInstanceOf(RuntimeException.class);
    assertThat(primary().redis().opsForList().range(counterKey, 0, -1))
        .containsExactly("counter-sentinel");
    assertThat(primary().redis().getExpire(counterKey)).isEqualTo(-1L);

    List<String> wrongEvents = KEYS.slidingWindowLog(digest('e'));
    primary().redis().opsForValue().set(wrongEvents.get(0), "events-sentinel");
    assertThatThrownBy(() -> primary().slidingLog().reserve(logPolicy, digest('e'), 1, 1))
        .isInstanceOf(RuntimeException.class);
    assertThat(primary().redis().opsForValue().get(wrongEvents.get(0)))
        .isEqualTo("events-sentinel");
    assertThat(primary().redis().hasKey(wrongEvents.get(1))).isFalse();

    List<String> wrongMetadata = KEYS.slidingWindowLog(digest('f'));
    primary().redis().opsForValue().set(wrongMetadata.get(1), "metadata-sentinel");
    assertThatThrownBy(() -> primary().slidingLog().reserve(logPolicy, digest('f'), 1, 1))
        .isInstanceOf(RuntimeException.class);
    assertThat(primary().redis().hasKey(wrongMetadata.get(0))).isFalse();
    assertThat(primary().redis().opsForValue().get(wrongMetadata.get(1)))
        .isEqualTo("metadata-sentinel");
  }

  @Test
  void corruptShapesAndValuesAreRejectedWithoutPartialRepairOrExpiry() {
    TokenBucketPolicy tokenPolicy = tokenPolicy("corrupt-token", 5, 5, Duration.ofSeconds(1));
    SlidingWindowLogPolicy logPolicy = logPolicy("corrupt-log", 5, Duration.ofSeconds(10));
    SlidingWindowCounterPolicy counterPolicy =
        counterPolicy("corrupt-counter", 5, Duration.ofSeconds(10));

    String tokenKey = KEYS.tokenBucket(digest('1'));
    primary().redis().opsForHash().put(tokenKey, "balance_units", "not-an-integer");
    primary().redis().opsForHash().put(tokenKey, "last_ms", "1");
    Map<Object, Object> tokenBefore = primary().redis().opsForHash().entries(tokenKey);
    assertThatThrownBy(() -> primary().tokenBucket().reserve(tokenPolicy, digest('1'), 1, 1))
        .isInstanceOf(RuntimeException.class);
    assertThat(primary().redis().opsForHash().entries(tokenKey)).isEqualTo(tokenBefore);
    assertThat(primary().redis().getExpire(tokenKey)).isEqualTo(-1L);

    String counterKey = KEYS.slidingWindowCounter(digest('2'));
    primary().redis().opsForHash().put(counterKey, "current_start_ms", "1000");
    primary().redis().opsForHash().put(counterKey, "current_count", "1");
    primary().redis().opsForHash().put(counterKey, "previous_count", "0");
    primary().redis().opsForHash().put(counterKey, "last_ms", "11000");
    Map<Object, Object> counterBefore = primary().redis().opsForHash().entries(counterKey);
    assertThatThrownBy(() -> primary().slidingCounter().reserve(counterPolicy, digest('2'), 1, 1))
        .isInstanceOf(RuntimeException.class);
    assertThat(primary().redis().opsForHash().entries(counterKey)).isEqualTo(counterBefore);
    assertThat(primary().redis().getExpire(counterKey)).isEqualTo(-1L);

    List<String> logKeys = KEYS.slidingWindowLog(digest('3'));
    primary().redis().opsForZSet().add(logKeys.get(0), "orphan-event", 1);
    assertThatThrownBy(() -> primary().slidingLog().reserve(logPolicy, digest('3'), 1, 1))
        .isInstanceOf(RuntimeException.class);
    assertThat(primary().redis().opsForZSet().rangeWithScores(logKeys.get(0), 0, -1))
        .extracting(tuple -> tuple.getValue())
        .containsExactly("orphan-event");
    assertThat(primary().redis().hasKey(logKeys.get(1))).isFalse();
    assertThat(primary().redis().getExpire(logKeys.get(0))).isEqualTo(-1L);

    List<String> extraMetadata = KEYS.slidingWindowLog(digest('4'));
    primary().redis().opsForHash().put(extraMetadata.get(1), "last_ms", "1");
    primary().redis().opsForHash().put(extraMetadata.get(1), "sequence", "0");
    primary().redis().opsForHash().put(extraMetadata.get(1), "unexpected", "sentinel");
    Map<Object, Object> metadataBefore =
        primary().redis().opsForHash().entries(extraMetadata.get(1));
    assertThatThrownBy(() -> primary().slidingLog().reserve(logPolicy, digest('4'), 1, 1))
        .isInstanceOf(RuntimeException.class);
    assertThat(primary().redis().opsForHash().entries(extraMetadata.get(1)))
        .isEqualTo(metadataBefore);
    assertThat(primary().redis().hasKey(extraMetadata.get(0))).isFalse();

    String oversizedDigest = digest('5');
    List<String> oversizedLog = KEYS.slidingWindowLog(oversizedDigest);
    long oversizedNow = redisTimeMillis();
    primary().redis().opsForZSet().add(oversizedLog.get(0), "expired-event", oversizedNow - 10_001);
    for (int index = 1; index <= 7; index++) {
      primary()
          .redis()
          .opsForZSet()
          .add(oversizedLog.get(0), "live-event-" + index, oversizedNow - index);
    }
    primary().redis().opsForHash().put(oversizedLog.get(1), "last_ms", Long.toString(oversizedNow));
    primary().redis().opsForHash().put(oversizedLog.get(1), "sequence", "8");
    var oversizedEventsBefore =
        primary().redis().opsForZSet().rangeWithScores(oversizedLog.get(0), 0, -1);
    Map<Object, Object> oversizedMetadataBefore =
        primary().redis().opsForHash().entries(oversizedLog.get(1));

    assertThatThrownBy(() -> primary().slidingLog().reserve(logPolicy, oversizedDigest, 1, 1))
        .isInstanceOf(RuntimeException.class);
    assertThat(primary().redis().opsForZSet().rangeWithScores(oversizedLog.get(0), 0, -1))
        .isEqualTo(oversizedEventsBefore);
    assertThat(primary().redis().opsForHash().entries(oversizedLog.get(1)))
        .isEqualTo(oversizedMetadataBefore);
    assertThat(primary().redis().getExpire(oversizedLog.get(0))).isEqualTo(-1L);
    assertThat(primary().redis().getExpire(oversizedLog.get(1))).isEqualTo(-1L);
  }

  @Test
  void fixedPointTokenArithmeticUsesPersistedMonotonicTimeAndExactRetryBounds() {
    TokenBucketPolicy policy = tokenPolicy("token-arithmetic", 4, 2, Duration.ofSeconds(1));
    String digest = digest('5');
    String key = KEYS.tokenBucket(digest);
    long persistedFuture = redisTimeMillis() + 60_000;
    primary().redis().opsForHash().put(key, "balance_units", "1100");
    primary().redis().opsForHash().put(key, "last_ms", Long.toString(persistedFuture));

    BackendDecision allowed = primary().tokenBucket().reserve(policy, digest, 1, 2);
    BackendDecision denied = primary().tokenBucket().reserve(policy, digest, 1, 1);

    assertThat(allowed.allowed()).isTrue();
    assertThat(allowed.reservedPermits()).isOne();
    assertThat(allowed.centralRemaining()).isZero();
    assertThat(allowed.resetAfterMillis()).isEqualTo(1_950);
    assertThat(allowed.reservationValidForMillis()).isEqualTo(1_950);
    assertThat(allowed.effectiveNowEpochMillis()).isEqualTo(persistedFuture);
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.retryAfterMillis()).isEqualTo(450);
    assertThat(denied.resetAfterMillis()).isEqualTo(1_950);
    assertThat(denied.effectiveNowEpochMillis()).isEqualTo(persistedFuture);
    assertThat(primary().redis().opsForHash().get(key, "balance_units")).isEqualTo("100");
    assertThat(primary().redis().opsForHash().get(key, "last_ms"))
        .isEqualTo(Long.toString(persistedFuture));
    assertThat(primary().redis().getExpire(key, TimeUnit.MILLISECONDS)).isBetween(2_500L, 3_000L);
  }

  @Test
  void tokenBucketPreservesEveryFractionalRefillUnitAcrossDeniedCalls() {
    TokenBucketPolicy policy = tokenPolicy("token-fraction", 100, 4, Duration.ofSeconds(1));
    String digest = digest('6');
    String key = KEYS.tokenBucket(digest);
    long initialLast = redisTimeMillis() - 250;
    long initialBalance = 1;
    primary()
        .redis()
        .opsForHash()
        .putAll(
            key,
            Map.of(
                "balance_units", Long.toString(initialBalance),
                "last_ms", Long.toString(initialLast)));

    BackendDecision first = primary().tokenBucket().reserve(policy, digest, 100, 100);
    long firstBalance = hashLong(key, "balance_units");
    long firstElapsed = first.effectiveNowEpochMillis() - initialLast;

    assertThat(first.allowed()).isFalse();
    assertThat(firstElapsed).isBetween(250L, 5_000L);
    assertThat(firstBalance).isEqualTo(initialBalance + firstElapsed * policy.refillTokens());
    assertThat(firstBalance % policy.refillPeriod().toMillis()).isNotZero();
    assertThat(firstBalance % policy.refillTokens()).isEqualTo(initialBalance);

    BackendDecision second = primary().tokenBucket().reserve(policy, digest, 100, 100);
    long secondBalance = hashLong(key, "balance_units");
    long secondElapsed = second.effectiveNowEpochMillis() - first.effectiveNowEpochMillis();

    assertThat(second.allowed()).isFalse();
    assertThat(secondElapsed).isBetween(0L, 5_000L);
    assertThat(secondBalance).isEqualTo(firstBalance + secondElapsed * policy.refillTokens());
    assertThat(secondBalance % policy.refillPeriod().toMillis()).isNotZero();
    assertThat(secondBalance % policy.refillTokens()).isEqualTo(initialBalance);
  }

  @Test
  void slidingLogUsesAnExclusiveCutoffAndWeightedKthOldestRetry() {
    SlidingWindowLogPolicy policy = logPolicy("log-cutoff", 5, Duration.ofSeconds(10));
    String digest = digest('6');
    List<String> keys = KEYS.slidingWindowLog(digest);
    long effectiveNow = redisTimeMillis() + 60_000;
    long cutoff = effectiveNow - policy.window().toMillis();
    primary().redis().opsForZSet().add(keys.get(0), "at-cutoff", cutoff);
    primary().redis().opsForZSet().add(keys.get(0), "just-live", cutoff + 1);
    primary().redis().opsForZSet().add(keys.get(0), "second-live", effectiveNow - 7_000);
    primary().redis().opsForZSet().add(keys.get(0), "third-live", effectiveNow - 5_000);
    primary().redis().opsForZSet().add(keys.get(0), "fourth-live", effectiveNow - 3_000);
    primary().redis().opsForZSet().add(keys.get(0), "newest-live", effectiveNow - 1_000);
    primary().redis().opsForHash().put(keys.get(1), "last_ms", Long.toString(effectiveNow));
    primary().redis().opsForHash().put(keys.get(1), "sequence", "6");

    BackendDecision denied = primary().slidingLog().reserve(policy, digest, 3, 3);

    assertThat(denied.allowed()).isFalse();
    assertThat(denied.reservedPermits()).isZero();
    assertThat(denied.centralRemaining()).isZero();
    assertThat(denied.retryAfterMillis()).isEqualTo(5_000);
    assertThat(denied.resetAfterMillis()).isEqualTo(9_000);
    assertThat(denied.effectiveNowEpochMillis()).isEqualTo(effectiveNow);
    assertThat(primary().redis().opsForZSet().score(keys.get(0), "at-cutoff")).isNull();
    assertThat(primary().redis().opsForZSet().range(keys.get(0), 0, -1))
        .containsExactly("just-live", "second-live", "third-live", "fourth-live", "newest-live");
    assertThat(primary().redis().opsForHash().get(keys.get(1), "sequence")).isEqualTo("6");
  }

  @Test
  void slidingCounterRotatesExactlyOneBucketAndSearchesBothRetryBranches() {
    SlidingWindowCounterPolicy rotationPolicy =
        counterPolicy("counter-one-rotation", 10, Duration.ofMillis(COUNTER_WINDOW_MILLIS));
    String rotationDigest = digest('7');
    CounterWindow rotationWindow = freshCounterWindow(rotationDigest, COUNTER_WINDOW_MILLIS);
    String rotationKey = KEYS.slidingWindowCounter(rotationDigest);
    putCounterState(
        rotationKey,
        rotationWindow.bucketStartMillis() - COUNTER_WINDOW_MILLIS,
        7,
        3,
        rotationWindow.bucketStartMillis() - 1);

    BackendDecision rotated =
        primary().slidingCounter().reserve(rotationPolicy, rotationDigest, 1, 1);

    assertThat(rotated.allowed()).isTrue();
    assertThat(hashLong(rotationKey, "current_start_ms"))
        .isEqualTo(rotationWindow.bucketStartMillis());
    assertThat(hashLong(rotationKey, "previous_count")).isEqualTo(7);
    assertThat(hashLong(rotationKey, "current_count")).isOne();
    assertThat(hashLong(rotationKey, "last_ms"))
        .isBetween(rotationWindow.beforeMillis(), rotationWindow.bucketEndExclusiveMillis() - 1);

    long windowMillis = 1_000;
    SlidingWindowCounterPolicy retryPolicy =
        counterPolicy("counter-retry-branches", 10, Duration.ofMillis(windowMillis));
    long futureStart =
        Math.floorDiv(redisTimeMillis(), windowMillis) * windowMillis + 10 * windowMillis;
    long frozenNow = futureStart + 200;

    String previousDecayDigest = digest('8');
    String previousDecayKey = KEYS.slidingWindowCounter(previousDecayDigest);
    putCounterState(previousDecayKey, futureStart, 0, 10, frozenNow);
    BackendDecision beforeBoundary =
        primary().slidingCounter().reserve(retryPolicy, previousDecayDigest, 3, 3);

    assertThat(beforeBoundary.allowed()).isFalse();
    assertThat(beforeBoundary.centralRemaining()).isEqualTo(2);
    assertThat(beforeBoundary.retryAfterMillis()).isEqualTo(100);
    assertThat(beforeBoundary.resetAfterMillis()).isEqualTo(800);
    assertThat(beforeBoundary.effectiveNowEpochMillis()).isEqualTo(frozenNow);
    assertThat(hashLong(previousDecayKey, "current_count")).isZero();
    assertThat(hashLong(previousDecayKey, "previous_count")).isEqualTo(10);

    String currentMustRotateDigest = digest('9');
    String currentMustRotateKey = KEYS.slidingWindowCounter(currentMustRotateDigest);
    putCounterState(currentMustRotateKey, futureStart, 10, 0, frozenNow);
    BackendDecision afterBoundary =
        primary().slidingCounter().reserve(retryPolicy, currentMustRotateDigest, 1, 1);

    assertThat(afterBoundary.allowed()).isFalse();
    assertThat(afterBoundary.centralRemaining()).isZero();
    assertThat(afterBoundary.retryAfterMillis()).isEqualTo(900);
    assertThat(afterBoundary.resetAfterMillis()).isEqualTo(1_800);
    assertThat(afterBoundary.effectiveNowEpochMillis()).isEqualTo(frozenNow);
    assertThat(hashLong(currentMustRotateKey, "current_count")).isEqualTo(10);
    assertThat(hashLong(currentMustRotateKey, "previous_count")).isZero();
  }

  @Test
  void slidingCounterRotatesStaleBucketsAndReachesTheNearlyTwoLimitStateBound() {
    int limit = 37;
    long windowMillis = 60_000;
    SlidingWindowCounterPolicy policy =
        counterPolicy("counter-model-bound", limit, Duration.ofMillis(windowMillis));

    String staleDigest = digest('7');
    String staleKey = KEYS.slidingWindowCounter(staleDigest);
    long currentBucket = Math.floorDiv(redisTimeMillis(), windowMillis) * windowMillis;
    long twoBucketsOld = currentBucket - 2 * windowMillis;
    putCounterState(staleKey, twoBucketsOld, limit, limit, twoBucketsOld + windowMillis - 1);
    BackendDecision rotated = primary().slidingCounter().reserve(policy, staleDigest, 1, 1);
    assertThat(rotated.allowed()).isTrue();
    assertThat(primary().redis().opsForHash().get(staleKey, "previous_count")).isEqualTo("0");
    assertThat(primary().redis().opsForHash().get(staleKey, "current_count")).isEqualTo("1");

    String boundDigest = digest('8');
    String boundKey = KEYS.slidingWindowCounter(boundDigest);
    long futureStart =
        Math.floorDiv(redisTimeMillis() + 2 * windowMillis, windowMillis) * windowMillis;
    long futureLast = futureStart + windowMillis - 1;
    putCounterState(boundKey, futureStart, 0, limit, futureLast);
    BackendDecision nearlyDouble =
        primary().slidingCounter().reserve(policy, boundDigest, 1, limit);
    BackendDecision denied = primary().slidingCounter().reserve(policy, boundDigest, 1, 1);

    assertThat(nearlyDouble.allowed()).isTrue();
    assertThat(nearlyDouble.reservedPermits()).isEqualTo(limit - 1);
    assertThat(nearlyDouble.centralRemaining()).isZero();
    assertThat(nearlyDouble.resetAfterMillis()).isEqualTo(windowMillis + 1);
    assertThat(nearlyDouble.reservationValidForMillis()).isEqualTo(windowMillis + 1);
    assertThat(nearlyDouble.effectiveNowEpochMillis()).isEqualTo(futureLast);
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.retryAfterMillis()).isOne();
    long current = hashLong(boundKey, "current_count");
    long previous = hashLong(boundKey, "previous_count");
    assertThat(current + previous).isEqualTo(2L * limit - 1);
    long elapsed = futureLast - futureStart;
    long weightedNumerator = previous * (windowMillis - elapsed) + current * windowMillis;
    assertThat(weightedNumerator).isLessThanOrEqualTo(limit * windowMillis);
  }

  @Test
  void idleStateForAllAlgorithmsExpiresWithoutManualCleanup() {
    TokenBucketPolicy tokenPolicy = tokenPolicy("token-expiry", 1, 1, Duration.ofMillis(10));
    SlidingWindowLogPolicy logPolicy = logPolicy("log-expiry", 1, Duration.ofMillis(10));
    SlidingWindowCounterPolicy counterPolicy =
        counterPolicy("counter-expiry", 1, Duration.ofMillis(10));
    String digest = digest('9');
    String tokenKey = KEYS.tokenBucket(digest);
    List<String> logKeys = KEYS.slidingWindowLog(digest);
    String counterKey = KEYS.slidingWindowCounter(digest);

    assertThat(primary().tokenBucket().reserve(tokenPolicy, digest, 1, 1).allowed()).isTrue();
    assertThat(primary().slidingLog().reserve(logPolicy, digest, 1, 1).allowed()).isTrue();
    assertThat(primary().slidingCounter().reserve(counterPolicy, digest, 1, 1).allowed()).isTrue();
    assertThat(primary().redis().keys("hardening:*")).hasSize(4);

    await()
        .atMost(Duration.ofSeconds(4))
        .untilAsserted(() -> assertThat(primary().redis().keys("hardening:*")).isEmpty());
    assertThat(primary().redis().hasKey(tokenKey)).isFalse();
    assertThat(logKeys).allSatisfy(key -> assertThat(primary().redis().hasKey(key)).isFalse());
    assertThat(primary().redis().hasKey(counterKey)).isFalse();
  }

  @Test
  void tenIndependentConnectionsAdmitExactlyOneHundredUnitAndWeightedPermitsPerAlgorithm()
      throws Exception {
    Set<String> expectedClientNames = new HashSet<>();
    for (int index = 0; index < CLIENTS.size(); index++) {
      expectedClientNames.add("hardening-client-" + index);
      CLIENTS.get(index).redis().hasKey("connection-probe");
    }
    Set<String> actualClientNames =
        clientList().stream()
            .map(RedisClientInfo::getName)
            .collect(java.util.stream.Collectors.toSet());
    assertThat(actualClientNames).containsAll(expectedClientNames);

    for (Algorithm algorithm : Algorithm.values()) {
      RateLimitPolicy policy = concurrentPolicy(algorithm);
      String unitDigest = digest((char) ('a' + algorithm.ordinal()));
      CounterWindow unitCounterWindow =
          algorithm == Algorithm.SLIDING_WINDOW_COUNTER
              ? freshCounterWindow(unitDigest, COUNTER_WINDOW_MILLIS)
              : null;
      long unitTokenInitialLast =
          algorithm == Algorithm.TOKEN_BUCKET
              ? seedFullTokenBucket(unitDigest, (TokenBucketPolicy) policy)
              : -1;

      List<BackendDecision> unitWave =
          concurrentWave(
              client -> {
                List<Callable<BackendDecision>> calls = new ArrayList<>();
                for (int call = 0; call < 20; call++) {
                  calls.add(() -> client.reserve(algorithm, policy, unitDigest, 1, 1));
                }
                return calls;
              });
      long unitAfterMillis = redisTimeMillis();

      assertQuotaWave(unitWave, 1, 200);
      assertConcurrentState(
          algorithm,
          policy,
          unitDigest,
          CENTRAL_LIMIT,
          unitTokenInitialLast,
          unitCounterWindow,
          unitAfterMillis);

      String weightedDigest = digest((char) ('d' + algorithm.ordinal()));
      CounterWindow weightedCounterWindow =
          algorithm == Algorithm.SLIDING_WINDOW_COUNTER
              ? freshCounterWindow(weightedDigest, COUNTER_WINDOW_MILLIS)
              : null;
      long weightedTokenInitialLast =
          algorithm == Algorithm.TOKEN_BUCKET
              ? seedFullTokenBucket(weightedDigest, (TokenBucketPolicy) policy)
              : -1;

      List<BackendDecision> weightedWave =
          concurrentWave(
              client -> {
                List<Callable<BackendDecision>> calls = new ArrayList<>();
                for (int call = 0; call < 4; call++) {
                  calls.add(() -> client.reserve(algorithm, policy, weightedDigest, 5, 5));
                }
                return calls;
              });
      long weightedAfterMillis = redisTimeMillis();

      assertQuotaWave(weightedWave, 5, 40);
      assertConcurrentState(
          algorithm,
          policy,
          weightedDigest,
          CENTRAL_LIMIT,
          weightedTokenInitialLast,
          weightedCounterWindow,
          weightedAfterMillis);
    }
  }

  @Test
  void barrierControlledGetThenSetDemonstratesDeterministicOverAdmission() throws Exception {
    String key = "hardening:unsafe-get-then-set";
    primary().redis().opsForValue().set(key, "0");
    CyclicBarrier allClientsRead = new CyclicBarrier(INDEPENDENT_CLIENTS);
    ExecutorService executor = Executors.newFixedThreadPool(INDEPENDENT_CLIENTS);
    List<Future<Boolean>> decisions = new ArrayList<>();
    try {
      for (RedisClient client : CLIENTS) {
        decisions.add(
            executor.submit(
                () -> {
                  int observed = Integer.parseInt(client.redis().opsForValue().get(key));
                  boolean allowed = observed < 1;
                  allClientsRead.await(5, TimeUnit.SECONDS);
                  if (allowed) {
                    client.redis().opsForValue().set(key, Integer.toString(observed + 1));
                  }
                  return allowed;
                }));
      }
      int admitted = 0;
      for (Future<Boolean> decision : decisions) {
        if (decision.get(10, TimeUnit.SECONDS)) {
          admitted++;
        }
      }
      assertThat(admitted).isEqualTo(INDEPENDENT_CLIENTS);
      assertThat(primary().redis().opsForValue().get(key)).isEqualTo("1");
      assertThat(admitted)
          .isGreaterThan(Integer.parseInt(primary().redis().opsForValue().get(key)));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void realConnectionRefusalIsClassifiedAndTheSameClientRecoversWhenRedisAppears()
      throws Exception {
    int port = availableLoopbackPort();
    RedisClient recovering = RedisClient.connect("127.0.0.1", port, "recovery-client");
    RedisRateLimitBackend backend =
        new RedisRateLimitBackend(
            recovering.tokenBucket(),
            recovering.slidingLog(),
            recovering.slidingCounter(),
            new RedisAvailabilityClassifier(),
            new RateLimiterMetrics(new SimpleMeterRegistry()));
    TokenBucketPolicy policy = tokenPolicy("recovery", 5, 5, Duration.ofSeconds(1));
    FixedPortRedis recoveryRedis = new FixedPortRedis(port);
    try {
      assertThatThrownBy(() -> backend.reserve(policy, digest('0'), 1, 1))
          .isInstanceOfSatisfying(
              BackendUnavailableException.class,
              failure ->
                  assertThat(failure.category())
                      .isEqualTo(BackendUnavailableException.Category.CONNECTION));

      recoveryRedis.start();
      await()
          .atMost(Duration.ofSeconds(15))
          .pollInterval(Duration.ofMillis(100))
          .ignoreExceptionsInstanceOf(BackendUnavailableException.class)
          .untilAsserted(
              () -> assertThat(backend.reserve(policy, digest('0'), 1, 1).allowed()).isTrue());
      assertThat(recovering.redis().hasKey(KEYS.tokenBucket(digest('0')))).isTrue();
    } finally {
      recovering.close();
      recoveryRedis.stop();
    }
  }

  private static List<BackendDecision> concurrentWave(
      Function<RedisClient, List<Callable<BackendDecision>>> callsByClient) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(INDEPENDENT_CLIENTS);
    CountDownLatch ready = new CountDownLatch(INDEPENDENT_CLIENTS);
    CyclicBarrier start = new CyclicBarrier(INDEPENDENT_CLIENTS + 1);
    List<Future<List<BackendDecision>>> workers = new ArrayList<>();
    try {
      for (RedisClient client : CLIENTS) {
        workers.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await(5, TimeUnit.SECONDS);
                  List<BackendDecision> results = new ArrayList<>();
                  for (Callable<BackendDecision> call : callsByClient.apply(client)) {
                    results.add(call.call());
                  }
                  return results;
                }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.await(5, TimeUnit.SECONDS);
      List<BackendDecision> results = new ArrayList<>();
      for (Future<List<BackendDecision>> worker : workers) {
        results.addAll(worker.get(MAX_CONCURRENT_TEST_DURATION_MILLIS, TimeUnit.MILLISECONDS));
      }
      return results;
    } finally {
      start.reset();
      executor.shutdownNow();
    }
  }

  private static void assertQuotaWave(
      List<BackendDecision> decisions, int weight, int expectedAttempts) {
    assertThat(decisions).hasSize(expectedAttempts);
    assertThat(decisions.stream().mapToInt(BackendDecision::reservedPermits).sum())
        .isEqualTo(CENTRAL_LIMIT)
        .isLessThanOrEqualTo(CENTRAL_LIMIT);
    assertThat(decisions.stream().filter(BackendDecision::allowed).count())
        .isEqualTo(CENTRAL_LIMIT / weight);
    assertThat(decisions)
        .allSatisfy(decision -> assertThat(decision.reservedPermits()).isIn(0, weight))
        .anySatisfy(decision -> assertThat(decision.allowed()).isFalse());
  }

  private static void assertConcurrentState(
      Algorithm algorithm,
      RateLimitPolicy policy,
      String digest,
      int grantedPermits,
      long tokenInitialLastMillis,
      CounterWindow counterWindow,
      long afterMillis) {
    switch (algorithm) {
      case TOKEN_BUCKET -> {
        TokenBucketPolicy tokenPolicy = (TokenBucketPolicy) policy;
        String key = KEYS.tokenBucket(digest);
        assertThat(primary().redis().opsForHash().entries(key))
            .containsOnlyKeys("balance_units", "last_ms");
        long permitCostUnits = tokenPolicy.refillPeriod().toMillis();
        long capacityUnits = tokenPolicy.capacity() * permitCostUnits;
        long finalLastMillis = hashLong(key, "last_ms");
        long elapsedMillis = finalLastMillis - tokenInitialLastMillis;
        long maximumAccruedUnits = elapsedMillis * tokenPolicy.refillTokens();
        long balanceUnits = hashLong(key, "balance_units");
        assertThat(grantedPermits).isEqualTo(tokenPolicy.capacity());
        assertThat(elapsedMillis)
            .isNotNegative()
            .isLessThan(positiveCeilingDivision(permitCostUnits, tokenPolicy.refillTokens()));
        assertThat(balanceUnits)
            .isBetween(0L, Math.min(capacityUnits, maximumAccruedUnits))
            .isLessThan(permitCostUnits);
      }
      case SLIDING_WINDOW_LOG -> {
        List<String> keys = KEYS.slidingWindowLog(digest);
        assertThat(primary().redis().opsForZSet().size(keys.get(0)))
            .isEqualTo((long) grantedPermits);
        assertThat(primary().redis().opsForHash().entries(keys.get(1)))
            .containsOnlyKeys("last_ms", "sequence");
        assertThat(hashLong(keys.get(1), "sequence")).isBetween(0L, (long) grantedPermits);
      }
      case SLIDING_WINDOW_COUNTER -> {
        String key = KEYS.slidingWindowCounter(digest);
        assertThat(counterWindow).isNotNull();
        assertThat(Math.floorDiv(counterWindow.beforeMillis(), COUNTER_WINDOW_MILLIS))
            .isEqualTo(Math.floorDiv(afterMillis, COUNTER_WINDOW_MILLIS));
        assertThat(afterMillis).isLessThan(counterWindow.bucketEndExclusiveMillis());
        assertThat(hashLong(key, "current_start_ms")).isEqualTo(counterWindow.bucketStartMillis());
        assertThat(hashLong(key, "current_count")).isEqualTo(grantedPermits);
        assertThat(hashLong(key, "previous_count")).isZero();
        assertThat(hashLong(key, "last_ms")).isBetween(counterWindow.beforeMillis(), afterMillis);
        assertThat(primary().redis().opsForHash().entries(key))
            .containsOnlyKeys("current_start_ms", "current_count", "previous_count", "last_ms");
      }
    }
  }

  private static RateLimitPolicy concurrentPolicy(Algorithm algorithm) {
    return switch (algorithm) {
      case TOKEN_BUCKET ->
          tokenPolicy("concurrent-token", CENTRAL_LIMIT, CENTRAL_LIMIT, Duration.ofHours(24));
      case SLIDING_WINDOW_LOG -> logPolicy("concurrent-log", CENTRAL_LIMIT, Duration.ofHours(24));
      case SLIDING_WINDOW_COUNTER ->
          counterPolicy(
              "concurrent-counter", CENTRAL_LIMIT, Duration.ofMillis(COUNTER_WINDOW_MILLIS));
    };
  }

  private static long seedFullTokenBucket(String digest, TokenBucketPolicy policy) {
    String key = KEYS.tokenBucket(digest);
    long initialLastMillis = redisTimeMillis();
    long capacityUnits = policy.capacity() * policy.refillPeriod().toMillis();
    primary()
        .redis()
        .opsForHash()
        .putAll(
            key,
            Map.of(
                "balance_units", Long.toString(capacityUnits),
                "last_ms", Long.toString(initialLastMillis)));
    return initialLastMillis;
  }

  private static CounterWindow freshCounterWindow(String digest, long windowMillis) {
    String key = KEYS.slidingWindowCounter(digest);
    assertThat(primary().redis().hasKey(key)).isFalse();

    long now = redisTimeMillis();
    long bucketStart = Math.floorDiv(now, windowMillis) * windowMillis;
    long bucketEnd = bucketStart + windowMillis;
    long remaining = bucketEnd - now;
    if (remaining <= MAX_CONCURRENT_TEST_DURATION_MILLIS) {
      long boundary = bucketEnd;
      await()
          .atMost(Duration.ofMillis(remaining + 2_000))
          .pollInterval(Duration.ofMillis(10))
          .until(() -> redisTimeMillis() >= boundary);
      now = redisTimeMillis();
      bucketStart = Math.floorDiv(now, windowMillis) * windowMillis;
      bucketEnd = bucketStart + windowMillis;
      remaining = bucketEnd - now;
    }

    assertThat(remaining).isGreaterThan(MAX_CONCURRENT_TEST_DURATION_MILLIS);
    assertThat(primary().redis().hasKey(key)).isFalse();
    return new CounterWindow(now, bucketStart, bucketEnd);
  }

  private static long positiveCeilingDivision(long dividend, long divisor) {
    return dividend == 0 ? 0 : ((dividend - 1) / divisor) + 1;
  }

  private static TokenBucketPolicy tokenPolicy(
      String id, int capacity, int refillTokens, Duration refillPeriod) {
    return new TokenBucketPolicy(
        id,
        1,
        capacity,
        refillTokens,
        refillPeriod,
        FailureMode.FAIL_CLOSED,
        LocalCacheSettings.disabled());
  }

  private static SlidingWindowLogPolicy logPolicy(String id, int limit, Duration window) {
    return new SlidingWindowLogPolicy(
        id, 1, limit, window, FailureMode.FAIL_CLOSED, LocalCacheSettings.disabled());
  }

  private static SlidingWindowCounterPolicy counterPolicy(String id, int limit, Duration window) {
    return new SlidingWindowCounterPolicy(
        id, 1, limit, window, FailureMode.FAIL_CLOSED, LocalCacheSettings.disabled());
  }

  private static void putCounterState(
      String key, long currentStart, long currentCount, long previousCount, long lastMillis) {
    primary()
        .redis()
        .opsForHash()
        .putAll(
            key,
            Map.of(
                "current_start_ms", Long.toString(currentStart),
                "current_count", Long.toString(currentCount),
                "previous_count", Long.toString(previousCount),
                "last_ms", Long.toString(lastMillis)));
  }

  private static long hashLong(String key, String field) {
    return Long.parseLong((String) primary().redis().opsForHash().get(key, field));
  }

  private static long redisTimeMillis() {
    try (var connection = primary().factory().getConnection()) {
      return connection.serverCommands().time(TimeUnit.MILLISECONDS);
    }
  }

  private static List<RedisClientInfo> clientList() {
    try (var connection = primary().factory().getConnection()) {
      return connection.serverCommands().getClientList();
    }
  }

  private static int availableLoopbackPort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static RedisClient primary() {
    return CLIENTS.getFirst();
  }

  private static String digest(char value) {
    return String.valueOf(value).repeat(64);
  }

  private record CounterWindow(
      long beforeMillis, long bucketStartMillis, long bucketEndExclusiveMillis) {}

  private record RedisClient(
      LettuceConnectionFactory factory,
      StringRedisTemplate redis,
      TokenBucketRedisAlgorithm tokenBucket,
      SlidingWindowLogRedisAlgorithm slidingLog,
      SlidingWindowCounterRedisAlgorithm slidingCounter) {
    static RedisClient connect(String host, int port, String clientName) {
      RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(host, port);
      LettuceClientConfiguration clientConfiguration =
          LettuceClientConfiguration.builder()
              .clientName(clientName)
              .commandTimeout(Duration.ofMillis(750))
              .shutdownTimeout(Duration.ZERO)
              .clientOptions(
                  ClientOptions.builder()
                      .socketOptions(
                          SocketOptions.builder().connectTimeout(Duration.ofMillis(300)).build())
                      .build())
              .build();
      LettuceConnectionFactory factory =
          new LettuceConnectionFactory(standalone, clientConfiguration);
      factory.afterPropertiesSet();
      factory.start();
      StringRedisTemplate redis = new StringRedisTemplate(factory);
      redis.afterPropertiesSet();
      LuaScriptRegistry scripts = new LuaScriptRegistry();
      RateLimiterMetrics metrics = new RateLimiterMetrics(new SimpleMeterRegistry());
      return new RedisClient(
          factory,
          redis,
          new TokenBucketRedisAlgorithm(redis, KEYS, scripts, metrics),
          new SlidingWindowLogRedisAlgorithm(redis, KEYS, scripts, metrics),
          new SlidingWindowCounterRedisAlgorithm(redis, KEYS, scripts, metrics));
    }

    BackendDecision reserve(
        Algorithm algorithm,
        RateLimitPolicy policy,
        String digest,
        int minimumPermits,
        int desiredPermits) {
      return switch (algorithm) {
        case TOKEN_BUCKET -> tokenBucket.reserve(policy, digest, minimumPermits, desiredPermits);
        case SLIDING_WINDOW_LOG ->
            slidingLog.reserve(policy, digest, minimumPermits, desiredPermits);
        case SLIDING_WINDOW_COUNTER ->
            slidingCounter.reserve(policy, digest, minimumPermits, desiredPermits);
      };
    }

    void close() {
      factory.destroy();
    }
  }

  private static final class FixedPortRedis extends GenericContainer<FixedPortRedis> {
    FixedPortRedis(int hostPort) {
      super(REDIS_IMAGE);
      addFixedExposedPort(hostPort, 6379);
    }
  }
}
