package dev.ratelimiter.service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ratelimiter.core.Algorithm;
import dev.ratelimiter.core.DecisionReason;
import dev.ratelimiter.core.DecisionSource;
import dev.ratelimiter.core.DistributedRateLimiter;
import dev.ratelimiter.core.RateLimitDecision;
import dev.ratelimiter.core.RateLimitRequest;
import dev.ratelimiter.service.api.RateLimitController;
import dev.ratelimiter.service.config.ServiceSecurityProperties;
import dev.ratelimiter.service.web.RequestIdFilter;
import dev.ratelimiter.service.web.ServletProblemWriter;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = RateLimitController.class,
    properties = {
      "rate-limiter-service.security.enabled=true",
      "rate-limiter-service.security.api-key=correct-horse-battery-staple"
    })
@EnableConfigurationProperties(ServiceSecurityProperties.class)
@Import({SecurityConfiguration.class, RequestIdFilter.class, ServletProblemWriter.class})
class ApiKeySecurityWebTest {

  @Autowired MockMvc mockMvc;

  @MockitoBean DistributedRateLimiter rateLimiter;

  @BeforeEach
  void allowedDecision() {
    when(rateLimiter.tryAcquire(any(RateLimitRequest.class)))
        .thenReturn(
            new RateLimitDecision(
                true,
                "api-standard",
                1,
                Algorithm.TOKEN_BUCKET,
                100,
                99,
                1,
                0,
                Instant.parse("2030-01-01T00:00:00Z"),
                DecisionSource.REDIS,
                DecisionReason.ALLOWED,
                false,
                false));
  }

  @Test
  void protectedApiReturnsProblem401ForMissingInvalidAndDuplicateKeys() throws Exception {
    String body = "{\"policyId\":\"api-standard\",\"key\":\"user:1\"}";

    mockMvc
        .perform(
            post("/api/v1/rate-limits/check")
                .header(RequestIdFilter.HEADER_NAME, "auth-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(header().string(RequestIdFilter.HEADER_NAME, "auth-test"))
        .andExpect(jsonPath("$.title").value("Unauthorized"))
        .andExpect(jsonPath("$.requestId").value("auth-test"));

    mockMvc
        .perform(
            post("/api/v1/rate-limits/check")
                .header(ApiKeyAuthenticationFilter.HEADER_NAME, "incorrect")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized());

    mockMvc
        .perform(
            post("/api/v1/rate-limits/check")
                .header(
                    ApiKeyAuthenticationFilter.HEADER_NAME,
                    "correct-horse-battery-staple",
                    "second")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void validApiKeyAuthenticatesWithoutCredentialsInTheResponse() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/rate-limits/check")
                .header(ApiKeyAuthenticationFilter.HEADER_NAME, "correct-horse-battery-staple")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"policyId\":\"api-standard\",\"key\":\"user:1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(true))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("correct-horse-battery-staple"))));
  }

  @Test
  void productionRouteMatrixProtectsSensitiveAndPermitsOperationsEndpoints() throws Exception {
    for (String protectedPath :
        new String[] {"/v3/api-docs", "/swagger-ui/index.html", "/actuator/prometheus"}) {
      mockMvc.perform(get(protectedPath)).andExpect(status().isUnauthorized());
      mockMvc
          .perform(
              get(protectedPath)
                  .header(ApiKeyAuthenticationFilter.HEADER_NAME, "correct-horse-battery-staple"))
          .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    for (String publicPath :
        new String[] {
          "/actuator/health",
          "/actuator/health/liveness",
          "/actuator/health/readiness",
          "/actuator/info"
        }) {
      mockMvc
          .perform(get(publicPath))
          .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    mockMvc
        .perform(
            get("/not-exposed")
                .header(ApiKeyAuthenticationFilter.HEADER_NAME, "correct-horse-battery-staple"))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }
}
