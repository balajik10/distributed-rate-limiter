package dev.ratelimiter.core;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Service-provider interface for trusted, server-defined rate-limit policies. */
@FunctionalInterface
public interface PolicyProvider {
  Optional<RateLimitPolicy> findById(String policyId);

  default RateLimitPolicy requireById(String policyId) {
    RateLimitConstraints.requirePolicyId(policyId);
    Optional<RateLimitPolicy> policy =
        Objects.requireNonNull(findById(policyId), "findById must not return null");
    return policy.orElseThrow(() -> new UnknownPolicyException(policyId));
  }

  /** Returns a stable snapshot for inspection endpoints, or an empty collection if unsupported. */
  default Collection<RateLimitPolicy> policies() {
    return List.of();
  }
}
