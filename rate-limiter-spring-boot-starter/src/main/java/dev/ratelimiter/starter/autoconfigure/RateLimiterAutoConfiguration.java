package dev.ratelimiter.starter.autoconfigure;

import dev.ratelimiter.core.DistributedRateLimiter;
import dev.ratelimiter.core.PolicyProvider;
import dev.ratelimiter.starter.DefaultDistributedRateLimiter;
import dev.ratelimiter.starter.backend.LuaScriptRegistry;
import dev.ratelimiter.starter.backend.RateLimiterRedisHealthIndicator;
import dev.ratelimiter.starter.backend.RedisAvailabilityClassifier;
import dev.ratelimiter.starter.backend.RedisRateLimitBackend;
import dev.ratelimiter.starter.backend.SlidingWindowCounterRedisAlgorithm;
import dev.ratelimiter.starter.backend.SlidingWindowLogRedisAlgorithm;
import dev.ratelimiter.starter.backend.TokenBucketRedisAlgorithm;
import dev.ratelimiter.starter.cache.LocalLeaseManager;
import dev.ratelimiter.starter.config.RateLimiterProperties;
import dev.ratelimiter.starter.hash.KeyHasher;
import dev.ratelimiter.starter.hash.RedisKeyFactory;
import dev.ratelimiter.starter.metrics.RateLimiterMetrics;
import dev.ratelimiter.starter.policy.PolicyFactory;
import dev.ratelimiter.starter.policy.YamlPolicyRegistry;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration
@AutoConfigureAfter(RedisAutoConfiguration.class)
@ConditionalOnClass({DistributedRateLimiter.class, StringRedisTemplate.class})
@ConditionalOnMissingBean(DistributedRateLimiter.class)
@EnableConfigurationProperties(RateLimiterProperties.class)
public class RateLimiterAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  PolicyFactory rateLimiterPolicyFactory() {
    return new PolicyFactory();
  }

  @Bean
  @ConditionalOnMissingBean(PolicyProvider.class)
  YamlPolicyRegistry rateLimiterPolicyProvider(
      RateLimiterProperties properties, PolicyFactory policyFactory) {
    return new YamlPolicyRegistry(properties, policyFactory);
  }

  @Bean
  @ConditionalOnMissingBean
  KeyHasher rateLimiterKeyHasher(RateLimiterProperties properties) {
    return new KeyHasher(properties.getKeyHashSecret());
  }

  @Bean
  @ConditionalOnMissingBean
  RedisKeyFactory rateLimiterRedisKeyFactory(RateLimiterProperties properties) {
    return new RedisKeyFactory(properties.getKeyPrefix());
  }

  @Bean
  @ConditionalOnMissingBean
  LuaScriptRegistry rateLimiterLuaScriptRegistry() {
    return new LuaScriptRegistry();
  }

  @Bean
  @ConditionalOnMissingBean
  RedisAvailabilityClassifier rateLimiterRedisAvailabilityClassifier() {
    return new RedisAvailabilityClassifier();
  }

  @Bean
  @ConditionalOnMissingBean
  RateLimiterMetrics rateLimiterMetrics(ObjectProvider<MeterRegistry> registries) {
    return new RateLimiterMetrics(registries.getIfAvailable(SimpleMeterRegistry::new));
  }

  @Bean
  @ConditionalOnMissingBean
  TokenBucketRedisAlgorithm tokenBucketRedisAlgorithm(
      StringRedisTemplate redis,
      RedisKeyFactory keys,
      LuaScriptRegistry scripts,
      RateLimiterMetrics metrics) {
    return new TokenBucketRedisAlgorithm(redis, keys, scripts, metrics);
  }

  @Bean
  @ConditionalOnMissingBean
  SlidingWindowLogRedisAlgorithm slidingWindowLogRedisAlgorithm(
      StringRedisTemplate redis,
      RedisKeyFactory keys,
      LuaScriptRegistry scripts,
      RateLimiterMetrics metrics) {
    return new SlidingWindowLogRedisAlgorithm(redis, keys, scripts, metrics);
  }

  @Bean
  @ConditionalOnMissingBean
  SlidingWindowCounterRedisAlgorithm slidingWindowCounterRedisAlgorithm(
      StringRedisTemplate redis,
      RedisKeyFactory keys,
      LuaScriptRegistry scripts,
      RateLimiterMetrics metrics) {
    return new SlidingWindowCounterRedisAlgorithm(redis, keys, scripts, metrics);
  }

  @Bean
  @ConditionalOnMissingBean
  RedisRateLimitBackend redisRateLimitBackend(
      TokenBucketRedisAlgorithm tokenBucket,
      SlidingWindowLogRedisAlgorithm slidingLog,
      SlidingWindowCounterRedisAlgorithm slidingCounter,
      RedisAvailabilityClassifier classifier,
      RateLimiterMetrics metrics) {
    return new RedisRateLimitBackend(tokenBucket, slidingLog, slidingCounter, classifier, metrics);
  }

  @Bean
  @ConditionalOnMissingBean
  LocalLeaseManager localLeaseManager(
      RedisRateLimitBackend backend, RateLimiterMetrics metrics, RateLimiterProperties properties) {
    return new LocalLeaseManager(backend, metrics, properties);
  }

  @Bean
  @ConditionalOnMissingBean(DistributedRateLimiter.class)
  DefaultDistributedRateLimiter distributedRateLimiter(
      PolicyProvider policies,
      KeyHasher keyHasher,
      RedisRateLimitBackend backend,
      LocalLeaseManager leases,
      RateLimiterMetrics metrics) {
    return new DefaultDistributedRateLimiter(policies, keyHasher, backend, leases, metrics);
  }

  @Bean(name = "rateLimiterRedisHealth")
  @ConditionalOnMissingBean(name = "rateLimiterRedisHealth")
  @ConditionalOnClass(HealthIndicator.class)
  RateLimiterRedisHealthIndicator rateLimiterRedisHealthIndicator(
      RedisConnectionFactory connectionFactory) {
    return new RateLimiterRedisHealthIndicator(connectionFactory);
  }

  @Bean
  LettuceClientConfigurationBuilderCustomizer rateLimiterLettuceTimeoutCustomizer(
      RateLimiterProperties properties) {
    Duration connectTimeout =
        positive(properties.getRedis().getConnectTimeout(), "connect-timeout");
    Duration commandTimeout =
        positive(properties.getRedis().getCommandTimeout(), "command-timeout");
    return builder ->
        builder
            .commandTimeout(commandTimeout)
            .clientOptions(
                ClientOptions.builder()
                    .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
                    .build());
  }

  private static Duration positive(Duration duration, String name) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalStateException("rate-limiter.redis." + name + " must be positive");
    }
    return duration;
  }
}
