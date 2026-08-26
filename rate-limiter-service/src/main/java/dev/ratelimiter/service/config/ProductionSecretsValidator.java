package dev.ratelimiter.service.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Prevents a secure deployment from starting with authentication or hashing silently disabled. */
@Component
@Profile({"prod", "production"})
public final class ProductionSecretsValidator {

  private final ServiceSecurityProperties securityProperties;
  private final String keyHashSecret;

  public ProductionSecretsValidator(
      ServiceSecurityProperties securityProperties,
      @Value("${rate-limiter.key-hash-secret:}") String keyHashSecret) {
    this.securityProperties = securityProperties;
    this.keyHashSecret = keyHashSecret;
  }

  @PostConstruct
  void validate() {
    if (!securityProperties.enabled()) {
      throw new IllegalStateException("API-key authentication must be enabled in production");
    }
    if (!StringUtils.hasText(securityProperties.apiKey())) {
      throw new IllegalStateException("RATE_LIMITER_API_KEY is required in production");
    }
    if (!StringUtils.hasText(keyHashSecret)) {
      throw new IllegalStateException("RATE_LIMITER_KEY_HASH_SECRET is required in production");
    }
  }
}
