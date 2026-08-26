package dev.ratelimiter.starter.cache;

import dev.ratelimiter.core.RateLimitPolicy;

public final class LocalPermitLease {
  private final RateLimitPolicy policy;
  private int remainingPermits;
  private final long deadlineNanos;
  private final long centralRemainingAtReservation;
  private final long resetAtEpochMillis;

  public LocalPermitLease(
      RateLimitPolicy policy,
      int remainingPermits,
      long deadlineNanos,
      long centralRemainingAtReservation,
      long resetAtEpochMillis) {
    if (remainingPermits < 0 || centralRemainingAtReservation < 0 || resetAtEpochMillis < 0) {
      throw new IllegalArgumentException("Invalid local permit lease");
    }
    this.policy = policy;
    this.remainingPermits = remainingPermits;
    this.deadlineNanos = deadlineNanos;
    this.centralRemainingAtReservation = centralRemainingAtReservation;
    this.resetAtEpochMillis = resetAtEpochMillis;
  }

  public RateLimitPolicy policy() {
    return policy;
  }

  public int remainingPermits() {
    return remainingPermits;
  }

  public long deadlineNanos() {
    return deadlineNanos;
  }

  public long centralRemainingAtReservation() {
    return centralRemainingAtReservation;
  }

  public long resetAtEpochMillis() {
    return resetAtEpochMillis;
  }

  int consumeOne() {
    if (remainingPermits == 0) {
      throw new IllegalStateException("Cannot consume an exhausted local lease");
    }
    return --remainingPermits;
  }

  int retire() {
    int retired = remainingPermits;
    remainingPermits = 0;
    return retired;
  }
}
