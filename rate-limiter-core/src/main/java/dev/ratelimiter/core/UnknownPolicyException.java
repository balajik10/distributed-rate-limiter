package dev.ratelimiter.core;

/** Raised when a caller selects a syntactically valid policy that is not configured. */
public final class UnknownPolicyException extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  private final String policyId;

  public UnknownPolicyException(String policyId) {
    super("Unknown rate-limit policy: " + policyId);
    this.policyId = RateLimitConstraints.requirePolicyId(policyId);
  }

  public String policyId() {
    return policyId;
  }
}
