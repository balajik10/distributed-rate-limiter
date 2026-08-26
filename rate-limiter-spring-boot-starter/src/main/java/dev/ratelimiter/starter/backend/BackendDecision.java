package dev.ratelimiter.starter.backend;

import java.util.List;

public record BackendDecision(
    boolean allowed,
    int reservedPermits,
    long centralRemaining,
    long retryAfterMillis,
    long resetAfterMillis,
    long reservationValidForMillis,
    long effectiveNowEpochMillis) {

  public BackendDecision {
    if (reservedPermits < 0
        || centralRemaining < 0
        || retryAfterMillis < 0
        || resetAfterMillis < 0
        || reservationValidForMillis < 0
        || effectiveNowEpochMillis < 0) {
      throw new IllegalArgumentException("backend decision contains a negative value");
    }
  }

  public static BackendDecision decode(Object raw, int minimumPermits, int desiredPermits) {
    if (!(raw instanceof List<?> values) || values.size() != 7) {
      throw new IllegalStateException("Redis script returned an invalid seven-integer result");
    }
    long allowedValue = integer(values.get(0), 0);
    if (allowedValue != 0 && allowedValue != 1) {
      throw malformed("allowed flag must be zero or one");
    }
    long reserved = integer(values.get(1), 1);
    long remaining = integer(values.get(2), 2);
    long retry = integer(values.get(3), 3);
    long reset = integer(values.get(4), 4);
    long validFor = integer(values.get(5), 5);
    long effectiveNow = integer(values.get(6), 6);
    if (reserved > Integer.MAX_VALUE) {
      throw malformed("reserved permits exceeds Java integer range");
    }

    boolean allowed = allowedValue == 1;
    if (allowed) {
      if (reserved < minimumPermits || reserved > desiredPermits || retry != 0 || validFor <= 0) {
        throw malformed("allowed result violates the reservation contract");
      }
    } else if (reserved != 0 || remaining >= minimumPermits || retry < 1 || validFor != 0) {
      throw malformed("denied result violates the reservation contract");
    }
    return new BackendDecision(
        allowed, (int) reserved, remaining, retry, reset, validFor, effectiveNow);
  }

  private static long integer(Object value, int index) {
    if (!(value instanceof Number number)) {
      throw malformed("element " + index + " is not numeric");
    }
    long integer = number.longValue();
    double floating = number.doubleValue();
    if (!Double.isFinite(floating) || floating != (double) integer || integer < 0) {
      throw malformed("element " + index + " is not a nonnegative integer");
    }
    return integer;
  }

  private static IllegalStateException malformed(String detail) {
    return new IllegalStateException("Malformed Redis rate-limiter result: " + detail);
  }
}
