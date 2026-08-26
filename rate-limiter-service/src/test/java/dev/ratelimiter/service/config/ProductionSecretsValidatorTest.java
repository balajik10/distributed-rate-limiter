package dev.ratelimiter.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class ProductionSecretsValidatorTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(ProductionConfiguration.class)
          .withPropertyValues("spring.profiles.active=prod");

  @Test
  void productionFailsWhenApiKeyIsMissing() {
    contextRunner
        .withPropertyValues(
            "rate-limiter-service.security.enabled=true",
            "rate-limiter-service.security.api-key=",
            "rate-limiter.key-hash-secret=hash-secret")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("RATE_LIMITER_API_KEY is required in production"));
  }

  @Test
  void productionFailsWhenHashSecretIsMissing() {
    contextRunner
        .withPropertyValues(
            "rate-limiter-service.security.enabled=true",
            "rate-limiter-service.security.api-key=api-secret",
            "rate-limiter.key-hash-secret=")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("RATE_LIMITER_KEY_HASH_SECRET is required in production"));
  }

  @Test
  void productionFailsIfAuthenticationWasExplicitlyDisabled() {
    contextRunner
        .withPropertyValues(
            "rate-limiter-service.security.enabled=false",
            "rate-limiter-service.security.api-key=api-secret",
            "rate-limiter.key-hash-secret=hash-secret")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("API-key authentication must be enabled in production"));
  }

  @Test
  void productionStartsWhenBothSecretsArePresent() {
    contextRunner
        .withPropertyValues(
            "rate-limiter-service.security.enabled=true",
            "rate-limiter-service.security.api-key=api-secret",
            "rate-limiter.key-hash-secret=hash-secret")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(ProductionSecretsValidator.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ServiceSecurityProperties.class)
  @Import(ProductionSecretsValidator.class)
  static class ProductionConfiguration {}
}
