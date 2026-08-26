package dev.ratelimiter.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rate-limiter-service.security")
public record ServiceSecurityProperties(boolean enabled, String apiKey) {

  public ServiceSecurityProperties {
    apiKey = apiKey == null ? "" : apiKey;
  }
}
