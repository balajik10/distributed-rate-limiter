package dev.ratelimiter.starter.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ratelimiter.core.DecisionReason;
import dev.ratelimiter.core.DecisionSource;
import dev.ratelimiter.core.DistributedRateLimiter;
import dev.ratelimiter.core.RateLimitDecision;
import dev.ratelimiter.starter.autoconfigure.RateLimiterAutoConfiguration;
import dev.ratelimiter.starter.backend.RedisRateLimitBackend;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class RateLimiterAutoConfigurationTest {
  private static final String[] VALID_POLICY = {
    "rate-limiter.policies.standard.version=1",
    "rate-limiter.policies.standard.algorithm=TOKEN_BUCKET",
    "rate-limiter.policies.standard.failure-mode=FAIL_OPEN",
    "rate-limiter.policies.standard.capacity=10",
    "rate-limiter.policies.standard.refill-tokens=10",
    "rate-limiter.policies.standard.refill-period=1s",
    "rate-limiter.policies.standard.local-cache.enabled=false",
    "rate-limiter.policies.standard.local-cache.max-lease-size=1",
    "rate-limiter.policies.standard.local-cache.max-lease-ttl=1ms",
    "rate-limiter.policies.standard.local-cache.expected-max-instances=1",
    "rate-limiter.policies.standard.local-cache.max-error-permits=0"
  };

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(RateLimiterAutoConfiguration.class))
          .withUserConfiguration(RedisTestConfiguration.class)
          .withPropertyValues(VALID_POLICY);

  @Test
  void createsThePublicLimiterWithoutServiceComponentScanning() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(DistributedRateLimiter.class);
          assertThat(context).hasBean("rateLimiterRedisHealth");
          assertThat(context.getBean(RateLimiterProperties.class).getPolicies())
              .containsKey("standard");
        });
  }

  @Test
  void backsOffForAUserProvidedLimiter() {
    DistributedRateLimiter custom =
        request ->
            new RateLimitDecision(
                true,
                request.policyId(),
                1,
                dev.ratelimiter.core.Algorithm.TOKEN_BUCKET,
                1,
                0,
                1,
                0,
                Instant.EPOCH,
                DecisionSource.REDIS,
                DecisionReason.ALLOWED,
                false,
                false);
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RateLimiterAutoConfiguration.class))
        .withBean("customLimiter", DistributedRateLimiter.class, () -> custom)
        .run(
            context -> {
              assertThat(context.getBeansOfType(DistributedRateLimiter.class)).hasSize(1);
              assertThat(context).doesNotHaveBean(RedisRateLimitBackend.class);
              assertThat(context).doesNotHaveBean(RateLimiterProperties.class);
            });
  }

  @Test
  void failsStartupForContradictoryPolicyFields() {
    runner
        .withPropertyValues("rate-limiter.policies.standard.limit=10")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage("Invalid rate-limiter policy 'standard': window fields");
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class RedisTestConfiguration {
    @Bean
    LettuceConnectionFactory redisConnectionFactory() {
      return new LettuceConnectionFactory("127.0.0.1", 1);
    }

    @Bean
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
      return new StringRedisTemplate(connectionFactory);
    }
  }
}
