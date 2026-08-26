package dev.ratelimiter.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Shared public bounds used by policies, requests, and backend adapters. */
public final class RateLimitConstraints {
  public static final int MAX_POLICY_ID_LENGTH = 64;
  public static final int MAX_KEY_LENGTH = 256;
  public static final int MAX_REQUEST_PERMITS = 100;
  public static final int MAX_LIMIT = 1_000_000;
  public static final int MAX_SLIDING_LOG_LIMIT = 100_000;
  public static final int MAX_LEASE_SIZE = 100;

  /**
   * Exclusive upper bound for integers passed through Lua numbers. Redis Lua uses IEEE-754 doubles,
   * so values must stay strictly below {@code 2^53 - 1}.
   */
  public static final long LUA_SAFE_INTEGER_EXCLUSIVE = 9_007_199_254_740_991L;

  public static final Duration MIN_ALGORITHM_DURATION = Duration.ofMillis(10);
  public static final Duration MAX_ALGORITHM_DURATION = Duration.ofHours(24);
  public static final Duration MAX_EMPTY_BUCKET_REFILL_DURATION = Duration.ofDays(30);

  private static final Pattern POLICY_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

  private RateLimitConstraints() {}

  /** Validates and returns a policy identifier. */
  public static String requirePolicyId(String policyId) {
    Objects.requireNonNull(policyId, "policyId must not be null");
    if (!POLICY_ID_PATTERN.matcher(policyId).matches()) {
      throw new IllegalArgumentException("policyId must match [a-z0-9][a-z0-9._-]{0,63}");
    }
    return policyId;
  }

  /** Validates and returns a logical key without including the key in an error message. */
  public static String requireLogicalKey(String key) {
    Objects.requireNonNull(key, "key must not be null");
    int characters = key.codePointCount(0, key.length());
    if (characters < 1 || characters > MAX_KEY_LENGTH || key.isBlank()) {
      throw new IllegalArgumentException("key must contain 1..256 characters and not be blank");
    }
    return key;
  }

  /** Validates a caller-requested permit count. */
  public static int requireRequestPermits(int permits) {
    return requireRange("permits", permits, 1, MAX_REQUEST_PERMITS);
  }

  /** Validates a policy version that may be passed through Redis Lua. */
  public static long requirePolicyVersion(long version) {
    return requireLuaInteger("version", version, 1);
  }

  /** Validates a millisecond epoch or arithmetic result before it crosses the Lua boundary. */
  public static long requireLuaEpochMillis(String name, long value) {
    return requireLuaInteger(name, value, 0);
  }

  /**
   * Verifies the token reset expression used by Lua: {@code effectiveNowMs + resetAfterMs + 1000}.
   */
  public static void validateTokenResetArithmetic(long effectiveNowMs, long resetAfterMs) {
    requireLuaEpochMillis("effectiveNowMs", effectiveNowMs);
    requireLuaEpochMillis("resetAfterMs", resetAfterMs);
    long withReset = safeAddBelowLuaLimit("token reset epoch", effectiveNowMs, resetAfterMs);
    safeAddBelowLuaLimit("token reset epoch with expiry padding", withReset, 1_000L);
  }

  static int requireRange(String name, int value, int minimum, int maximum) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(
          name + " must be between " + minimum + " and " + maximum + " (inclusive)");
    }
    return value;
  }

  static long requireLuaInteger(String name, long value, long minimum) {
    if (value < minimum || value >= LUA_SAFE_INTEGER_EXCLUSIVE) {
      throw new IllegalArgumentException(
          name
              + " must be between "
              + minimum
              + " and "
              + (LUA_SAFE_INTEGER_EXCLUSIVE - 1)
              + " (inclusive)");
    }
    return value;
  }

  static Duration requireAlgorithmDuration(String name, Duration duration) {
    Objects.requireNonNull(duration, name + " must not be null");
    if (duration.compareTo(MIN_ALGORITHM_DURATION) < 0
        || duration.compareTo(MAX_ALGORITHM_DURATION) > 0) {
      throw new IllegalArgumentException(name + " must be between 10ms and 24h (inclusive)");
    }
    requireWholeMilliseconds(name, duration);
    return duration;
  }

  static Duration requireLeaseDuration(Duration duration) {
    Objects.requireNonNull(duration, "maxLeaseTtl must not be null");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("maxLeaseTtl must be positive");
    }
    if (duration.compareTo(MAX_ALGORITHM_DURATION) > 0) {
      throw new IllegalArgumentException("maxLeaseTtl must not exceed 24h");
    }
    requireWholeMilliseconds("maxLeaseTtl", duration);
    return duration;
  }

  static long requireProductBelowLuaLimit(String name, long left, long right) {
    if (left < 0 || right < 0) {
      throw new IllegalArgumentException(name + " factors must be non-negative");
    }
    final long product;
    try {
      product = Math.multiplyExact(left, right);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException(
          name + " must remain below the Redis Lua integer bound", exception);
    }
    if (product >= LUA_SAFE_INTEGER_EXCLUSIVE) {
      throw new IllegalArgumentException(name + " must remain below 2^53 - 1");
    }
    return product;
  }

  static long safeAddBelowLuaLimit(String name, long left, long right) {
    if (left < 0 || right < 0) {
      throw new IllegalArgumentException(name + " terms must be non-negative");
    }
    final long sum;
    try {
      sum = Math.addExact(left, right);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException(
          name + " must remain below the Redis Lua integer bound", exception);
    }
    if (sum >= LUA_SAFE_INTEGER_EXCLUSIVE) {
      throw new IllegalArgumentException(name + " must remain below 2^53 - 1");
    }
    return sum;
  }

  static void requireInstantWithinLuaRange(String name, Instant instant) {
    Objects.requireNonNull(instant, name + " must not be null");
    final long epochMillis;
    try {
      epochMillis = instant.toEpochMilli();
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException(
          name + " must be representable as epoch milliseconds", exception);
    }
    requireLuaEpochMillis(name, epochMillis);
  }

  private static void requireWholeMilliseconds(String name, Duration duration) {
    if (!duration.minusMillis(duration.toMillis()).isZero()) {
      throw new IllegalArgumentException(name + " must use whole-millisecond precision");
    }
  }
}
