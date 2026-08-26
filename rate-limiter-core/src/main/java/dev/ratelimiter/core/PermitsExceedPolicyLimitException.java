package dev.ratelimiter.core;

/** Raised after policy resolution when a valid request count exceeds that policy's ceiling. */
public final class PermitsExceedPolicyLimitException extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  private final String policyId;
  private final int requestedPermits;
  private final int policyLimit;

  public PermitsExceedPolicyLimitException(String policyId, int requestedPermits, int policyLimit) {
    super(
        "Requested permits "
            + requestedPermits
            + " exceed policy '"
            + policyId
            + "' limit "
            + policyLimit);
    this.policyId = RateLimitConstraints.requirePolicyId(policyId);
    this.requestedPermits = RateLimitConstraints.requireRequestPermits(requestedPermits);
    this.policyLimit =
        RateLimitConstraints.requireRange(
            "policyLimit", policyLimit, 1, RateLimitConstraints.MAX_LIMIT);
  }

  public String policyId() {
    return policyId;
  }

  public int requestedPermits() {
    return requestedPermits;
  }

  public int policyLimit() {
    return policyLimit;
  }
}
