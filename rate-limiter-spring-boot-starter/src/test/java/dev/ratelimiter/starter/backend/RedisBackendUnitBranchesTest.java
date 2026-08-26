package dev.ratelimiter.starter.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ratelimiter.core.FailureMode;
import dev.ratelimiter.core.LocalCacheSettings;
import dev.ratelimiter.core.TokenBucketPolicy;
import dev.ratelimiter.starter.hash.RedisKeyFactory;
import dev.ratelimiter.starter.metrics.RateLimiterMetrics;
import io.lettuce.core.RedisConnectionException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisBackendUnitBranchesTest {
  private static final String DIGEST = "a".repeat(64);

  @Test
  void reservationBoundsAreValidatedBeforeAnyRedisAlgorithmRuns() {
    StubRedisTemplate redis = new StubRedisTemplate();
    RedisRateLimitBackend backend = backend(redis, new SimpleMeterRegistry());
    TokenBucketPolicy policy = policy();

    assertThatThrownBy(() -> backend.reserve(policy, DIGEST, 0, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reservation bounds");
    assertThatThrownBy(() -> backend.reserve(policy, DIGEST, 2, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reservation bounds");
    assertThatThrownBy(() -> backend.reserve(policy, DIGEST, 1, policy.limit() + 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reservation bounds");

    assertThat(redis.calls).hasValue(0);
  }

  @Test
  void successfulReservationIsDispatchedToThePolicyAlgorithm() {
    StubRedisTemplate redis = new StubRedisTemplate();
    redis.response = List.of(1L, 2L, 8L, 0L, 100L, 50L, 1_000L);
    RedisRateLimitBackend backend = backend(redis, new SimpleMeterRegistry());

    assertThat(backend.reserve(policy(), DIGEST, 1, 2))
        .isEqualTo(new BackendDecision(true, 2, 8, 0, 100, 50, 1_000));
    assertThat(redis.calls).hasValue(1);
  }

  @Test
  void availabilityFailureIsClassifiedOnceAndRecorded() {
    StubRedisTemplate redis = new StubRedisTemplate();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RedisRateLimitBackend backend = backend(redis, registry);
    RedisConnectionFailureException failure = new RedisConnectionFailureException("offline");
    redis.failure = failure;

    assertThatThrownBy(() -> backend.reserve(policy(), DIGEST, 1, 1))
        .isInstanceOfSatisfying(
            BackendUnavailableException.class,
            unavailable -> {
              assertThat(unavailable.category())
                  .isEqualTo(BackendUnavailableException.Category.CONNECTION);
              assertThat(unavailable.getCause()).isSameAs(failure);
            });
    assertThat(
            registry
                .get(RateLimiterMetrics.REDIS_ERRORS)
                .tag("policy", policy().id())
                .tag("category", BackendUnavailableException.Category.CONNECTION.name())
                .counter()
                .count())
        .isEqualTo(1.0D);
  }

  @Test
  void existingAvailabilityExceptionPassesThroughWithoutReclassification() {
    StubRedisTemplate redis = new StubRedisTemplate();
    RedisRateLimitBackend backend = backend(redis, new SimpleMeterRegistry());
    BackendUnavailableException failure =
        new BackendUnavailableException(
            BackendUnavailableException.Category.AMBIGUOUS_EXECUTION,
            new QueryTimeoutException("uncertain"));
    redis.failure = failure;

    assertThatThrownBy(() -> backend.reserve(policy(), DIGEST, 1, 1)).isSameAs(failure);
  }

  @Test
  void nonAvailabilityRuntimeFailurePassesThroughUnchanged() {
    StubRedisTemplate redis = new StubRedisTemplate();
    RedisRateLimitBackend backend = backend(redis, new SimpleMeterRegistry());
    IllegalStateException failure = new IllegalStateException("malformed Lua response");
    redis.failure = failure;

    assertThatThrownBy(() -> backend.reserve(policy(), DIGEST, 1, 1)).isSameAs(failure);
  }

  @Test
  void classifierHandlesEverySupportedWrapperAndTerminatesOnCauseCycles() {
    RedisAvailabilityClassifier classifier = new RedisAvailabilityClassifier();

    assertThat(classifier.classify(new QueryTimeoutException("timeout")))
        .contains(BackendUnavailableException.Category.AMBIGUOUS_EXECUTION);
    assertThat(classifier.classify(new RedisConnectionFailureException("spring connection")))
        .contains(BackendUnavailableException.Category.CONNECTION);
    assertThat(classifier.classify(new RedisConnectionException("lettuce connection")))
        .contains(BackendUnavailableException.Category.CONNECTION);

    RuntimeException first = new RuntimeException("first");
    RuntimeException second = new RuntimeException("second");
    first.initCause(second);
    second.initCause(first);
    assertThat(classifier.classify(first)).isEmpty();
  }

  @Test
  void redisHealthRequiresPongAndClosesTheConnection() {
    AtomicBoolean closed = new AtomicBoolean();
    RedisConnection connection = connection(() -> "pOnG", closed);
    RedisConnectionFactory factory = connectionFactory(() -> connection);

    var health = new RateLimiterRedisHealthIndicator(factory).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("backend", "redis");
    assertThat(closed).isTrue();
  }

  @Test
  void redisHealthIsDownForUnexpectedPingOrRuntimeFailure() {
    AtomicBoolean closed = new AtomicBoolean();
    RedisConnection unexpected = connection(() -> "LOADING", closed);
    RedisConnectionFactory unexpectedFactory = connectionFactory(() -> unexpected);

    assertThat(new RateLimiterRedisHealthIndicator(unexpectedFactory).health().getStatus())
        .isEqualTo(Status.DOWN);
    assertThat(closed).isTrue();

    AtomicBoolean failedPingClosed = new AtomicBoolean();
    RedisConnection failedPing =
        connection(
            () -> {
              throw new RedisConnectionException("ping failed");
            },
            failedPingClosed);
    assertThat(
            new RateLimiterRedisHealthIndicator(connectionFactory(() -> failedPing))
                .health()
                .getStatus())
        .isEqualTo(Status.DOWN);
    assertThat(failedPingClosed).isTrue();

    RedisConnectionFactory failedFactory =
        connectionFactory(
            () -> {
              throw new RedisConnectionException("offline");
            });
    assertThat(new RateLimiterRedisHealthIndicator(failedFactory).health().getStatus())
        .isEqualTo(Status.DOWN);
  }

  private static RedisRateLimitBackend backend(
      StubRedisTemplate redis, SimpleMeterRegistry registry) {
    RateLimiterMetrics metrics = new RateLimiterMetrics(registry);
    RedisKeyFactory keys = new RedisKeyFactory("unit");
    LuaScriptRegistry scripts = new LuaScriptRegistry();
    return new RedisRateLimitBackend(
        new TokenBucketRedisAlgorithm(redis, keys, scripts, metrics),
        new SlidingWindowLogRedisAlgorithm(redis, keys, scripts, metrics),
        new SlidingWindowCounterRedisAlgorithm(redis, keys, scripts, metrics),
        new RedisAvailabilityClassifier(),
        metrics);
  }

  private static RedisConnection connection(Supplier<String> ping, AtomicBoolean closed) {
    return (RedisConnection)
        Proxy.newProxyInstance(
            RedisConnection.class.getClassLoader(),
            new Class<?>[] {RedisConnection.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("ping")) {
                return ping.get();
              }
              if (method.getName().equals("close")) {
                closed.set(true);
                return null;
              }
              if (method.getDeclaringClass() == Object.class) {
                return method.invoke(proxy, arguments);
              }
              throw new UnsupportedOperationException(method.toString());
            });
  }

  private static RedisConnectionFactory connectionFactory(Supplier<RedisConnection> connection) {
    return (RedisConnectionFactory)
        Proxy.newProxyInstance(
            RedisConnectionFactory.class.getClassLoader(),
            new Class<?>[] {RedisConnectionFactory.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("getConnection")) {
                return connection.get();
              }
              if (method.getDeclaringClass() == Object.class) {
                return method.invoke(proxy, arguments);
              }
              throw new UnsupportedOperationException(method.toString());
            });
  }

  private static TokenBucketPolicy policy() {
    return new TokenBucketPolicy(
        "backend-policy",
        1,
        10,
        10,
        Duration.ofSeconds(1),
        FailureMode.FAIL_CLOSED,
        LocalCacheSettings.disabled());
  }

  private static final class StubRedisTemplate extends StringRedisTemplate {
    private final AtomicInteger calls = new AtomicInteger();
    private Object response;
    private RuntimeException failure;

    @Override
    @SuppressWarnings("unchecked")
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
      calls.incrementAndGet();
      if (failure != null) {
        throw failure;
      }
      return (T) response;
    }
  }
}
