package dev.ratelimiter.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ratelimiter.service.security.ApiKeyAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "rate-limiter-service.security.api-key=production-test-api-key",
      "rate-limiter.key-hash-secret=production-test-hash-secret",
      "springdoc.api-docs.enabled=false",
      "springdoc.swagger-ui.enabled=false",
      "spring.data.redis.host=127.0.0.1",
      "spring.data.redis.port=1"
    })
@ActiveProfiles("prod")
@AutoConfigureMockMvc
class ProductionProfileContextTest {

  @Autowired MockMvc mockMvc;

  @Test
  void secureProfileStartsWithBothSecretsAndEnforcesItsRouteMatrix() throws Exception {
    mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());

    mockMvc.perform(get("/api/v1/policies")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            get("/api/v1/policies")
                .header(ApiKeyAuthenticationFilter.HEADER_NAME, "production-test-api-key"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));

    mockMvc
        .perform(
            get("/v3/api-docs")
                .header(ApiKeyAuthenticationFilter.HEADER_NAME, "production-test-api-key"))
        .andExpect(status().isNotFound());
  }
}
