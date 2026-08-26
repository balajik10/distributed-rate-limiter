package dev.ratelimiter.starter.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ratelimiter.core.FailureMode;
import dev.ratelimiter.core.LocalCacheSettings;
import dev.ratelimiter.core.SlidingWindowCounterPolicy;
import dev.ratelimiter.core.SlidingWindowLogPolicy;
import dev.ratelimiter.core.TokenBucketPolicy;
import dev.ratelimiter.starter.hash.RedisKeyFactory;
import dev.ratelimiter.starter.metrics.RateLimiterMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisLuaAlgorithmsIT {
  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.0-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redis;
  private static RedisKeyFactory keys;
  private static TokenBucketRedisAlgorithm tokenBucket;
  private static SlidingWindowLogRedisAlgorithm slidingLog;
  private static SlidingWindowCounterRedisAlgorithm slidingCounter;

  @BeforeAll
  static void connect() {
    RedisStandaloneConfiguration configuration =
        new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
    connectionFactory = new LettuceConnectionFactory(configuration);
    connectionFactory.afterPropertiesSet();
    connectionFactory.start();
    redis = new StringRedisTemplate(connectionFactory);
    redis.afterPropertiesSet();
    keys = new RedisKeyFactory("integration");
    LuaScriptRegistry scripts = new LuaScriptRegistry();
    RateLimiterMetrics metrics = new RateLimiterMetrics(new SimpleMeterRegistry());
    tokenBucket = new TokenBucketRedisAlgorithm(redis, keys, scripts, metrics);
    slidingLog = new SlidingWindowLogRedisAlgorithm(redis, keys, scripts, metrics);
    slidingCounter = new SlidingWindowCounterRedisAlgorithm(redis, keys, scripts, metrics);
  }

  @AfterAll
  static void disconnect() {
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
  }

  @BeforeEach
  void flushRedis() {
    try (var connection = connectionFactory.getConnection()) {
      connection.serverCommands().flushDb();
    }
  }

  @Test
  void tokenBucketStartsFullSupportsWeightsAndUsesRedisTimeAndTtl() {
    TokenBucketPolicy policy =
        new TokenBucketPolicy(
            "token",
            1,
            5,
            1,
            Duration.ofSeconds(10),
            FailureMode.FAIL_OPEN,
            LocalCacheSettings.disabled());
    String digest = digest('a');

    BackendDecision allowed = tokenBucket.reserve(policy, digest, 2, 2);
    BackendDecision denied = tokenBucket.reserve(policy, digest, 4, 4);

    assertThat(allowed.allowed()).isTrue();
    assertThat(allowed.reservedPermits()).isEqualTo(2);
    assertThat(allowed.centralRemaining()).isEqualTo(3);
    assertThat(allowed.effectiveNowEpochMillis())
        .isBetween(System.currentTimeMillis() - 5_000, System.currentTimeMillis() + 5_000);
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.retryAfterMillis()).isPositive();
    assertThat(redis.getExpire(keys.tokenBucket(digest), TimeUnit.MILLISECONDS)).isPositive();
  }

  @Test
  void slidingLogCreatesCollisionFreeWeightedMembersAndNeverInsertsOnDenial() {
    SlidingWindowLogPolicy policy =
        new SlidingWindowLogPolicy(
            "log",
            1,
            3,
            Duration.ofSeconds(10),
            FailureMode.FAIL_CLOSED,
            LocalCacheSettings.disabled());
    String digest = digest('b');

    BackendDecision first = slidingLog.reserve(policy, digest, 3, 3);
    BackendDecision denied = slidingLog.reserve(policy, digest, 1, 1);

    assertThat(first.allowed()).isTrue();
    assertThat(first.centralRemaining()).isZero();
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.retryAfterMillis()).isPositive();
    assertThat(redis.opsForZSet().size(keys.slidingWindowLog(digest).getFirst())).isEqualTo(3);
    assertThat(keys.slidingWindowLog(digest))
        .allSatisfy(key -> assertThat(key).contains("{" + digest + "}"));
  }

  @Test
  void slidingCounterRotatesInsideLuaAndReturnsTheCommonContract() {
    SlidingWindowCounterPolicy policy =
        new SlidingWindowCounterPolicy(
            "counter",
            1,
            3,
            Duration.ofSeconds(10),
            FailureMode.FAIL_OPEN,
            LocalCacheSettings.disabled());
    String digest = digest('c');

    BackendDecision first = slidingCounter.reserve(policy, digest, 3, 3);
    BackendDecision denied = slidingCounter.reserve(policy, digest, 1, 1);

    assertThat(first.allowed()).isTrue();
    assertThat(first.reservationValidForMillis()).isPositive();
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.retryAfterMillis()).isPositive();
    assertThat(redis.getExpire(keys.slidingWindowCounter(digest), TimeUnit.MILLISECONDS))
        .isBetween(1L, 21_000L);
  }

  @Test
  void wrongKeyTypeFailsBeforeMutationAndIsNotAvailabilityFallback() {
    TokenBucketPolicy policy =
        new TokenBucketPolicy(
            "token",
            1,
            5,
            1,
            Duration.ofSeconds(10),
            FailureMode.FAIL_OPEN,
            LocalCacheSettings.disabled());
    String digest = digest('d');
    String key = keys.tokenBucket(digest);
    redis.opsForValue().set(key, "sentinel");

    assertThatThrownBy(() -> tokenBucket.reserve(policy, digest, 1, 1))
        .isInstanceOf(RuntimeException.class);
    assertThat(redis.opsForValue().get(key)).isEqualTo("sentinel");
  }

  @Test
  void scriptFlushTransparentlyFallsBackFromEvalshaToEval() {
    TokenBucketPolicy policy =
        new TokenBucketPolicy(
            "token",
            1,
            5,
            1,
            Duration.ofSeconds(10),
            FailureMode.FAIL_OPEN,
            LocalCacheSettings.disabled());
    assertThat(tokenBucket.reserve(policy, digest('e'), 1, 1).allowed()).isTrue();
    try (var connection = connectionFactory.getConnection()) {
      connection.scriptingCommands().scriptFlush();
    }

    assertThat(tokenBucket.reserve(policy, digest('f'), 1, 1).allowed()).isTrue();
  }

  @Test
  void twoHundredConcurrentCallsAcrossOneHundredTokensAdmitExactlyOneHundred() throws Exception {
    TokenBucketPolicy policy =
        new TokenBucketPolicy(
            "concurrent",
            1,
            100,
            100,
            Duration.ofHours(24),
            FailureMode.FAIL_CLOSED,
            LocalCacheSettings.disabled());
    String digest = digest('9');
    ExecutorService executor = Executors.newFixedThreadPool(20);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    try {
      for (int index = 0; index < 200; index++) {
        results.add(
            executor.submit(
                () -> {
                  start.await();
                  return tokenBucket.reserve(policy, digest, 1, 1).allowed();
                }));
      }
      start.countDown();
      int admitted = 0;
      for (Future<Boolean> result : results) {
        if (result.get(15, TimeUnit.SECONDS)) {
          admitted++;
        }
      }
      assertThat(admitted).isEqualTo(100);
    } finally {
      executor.shutdownNow();
    }
  }

  private static String digest(char value) {
    return String.valueOf(value).repeat(64);
  }
}
