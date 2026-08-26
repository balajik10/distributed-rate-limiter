package dev.ratelimiter.starter.policy;

import dev.ratelimiter.core.PolicyProvider;
import dev.ratelimiter.core.RateLimitPolicy;
import dev.ratelimiter.starter.config.RateLimiterProperties;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class YamlPolicyRegistry implements PolicyProvider {
  private final Map<String, RateLimitPolicy> policies;

  public YamlPolicyRegistry(RateLimiterProperties properties, PolicyFactory factory) {
    if (properties.getPolicies() == null || properties.getPolicies().isEmpty()) {
      throw new IllegalStateException("At least one rate-limiter policy must be configured");
    }
    Map<String, RateLimitPolicy> configured = new LinkedHashMap<>();
    properties
        .getPolicies()
        .forEach(
            (id, source) -> {
              RateLimitPolicy policy = factory.create(id, source);
              configured.put(id, policy);
            });
    this.policies = Collections.unmodifiableMap(configured);
  }

  @Override
  public Optional<RateLimitPolicy> findById(String policyId) {
    return Optional.ofNullable(policies.get(policyId));
  }

  @Override
  public Collection<RateLimitPolicy> policies() {
    return policies.values();
  }
}
