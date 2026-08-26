package dev.ratelimiter.service;

import dev.ratelimiter.service.config.ServiceSecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(ServiceSecurityProperties.class)
public class RateLimiterApplication {

  public static void main(String[] args) {
    SpringApplication.run(RateLimiterApplication.class, args);
  }
}
