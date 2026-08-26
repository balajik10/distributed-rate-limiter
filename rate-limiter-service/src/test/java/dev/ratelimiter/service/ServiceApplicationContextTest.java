package dev.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.data.redis.host=127.0.0.1",
      "spring.data.redis.port=1",
      "rate-limiter.redis.connect-timeout=100ms",
      "rate-limiter.redis.command-timeout=100ms"
    })
@AutoConfigureMockMvc
class ServiceApplicationContextTest {

  @Autowired MockMvc mockMvc;

  @Test
  void applicationStartsWithoutRedisAndLoadsExactlyTheSeedPolicies() throws Exception {
    mockMvc
        .perform(get("/api/v1/policies"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value("api-standard"))
        .andExpect(jsonPath("$[1].id").value("login-strict"))
        .andExpect(jsonPath("$[2].id").value("search-default"));
  }

  @Test
  void locallyLoadedPoliciesApplyTheirFailureModesWhenRedisWasUnavailableAtStartup()
      throws Exception {
    mockMvc
        .perform(
            post("/api/v1/rate-limits/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"policyId\":\"api-standard\",\"key\":\"startup-open\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(true))
        .andExpect(jsonPath("$.source").value("FAIL_OPEN"))
        .andExpect(jsonPath("$.degraded").value(true));

    mockMvc
        .perform(
            post("/api/v1/rate-limits/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"policyId\":\"login-strict\",\"key\":\"startup-closed\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.source").value("FAIL_CLOSED"))
        .andExpect(jsonPath("$.degraded").value(true));

    mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
  }

  @Test
  void openApiSwaggerAndOnlyTheConfiguredOperationsEndpointsAreExposed() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.paths['/api/v1/rate-limits/check']").exists());

    mockMvc
        .perform(get("/swagger-ui/index.html"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));

    mockMvc.perform(get("/actuator/health")).andExpect(result -> assertOperational(result));
    mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(result -> assertOperational(result));
    mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());

    mockMvc
        .perform(get("/actuator/env"))
        .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(200));
    mockMvc
        .perform(get("/actuator/metrics"))
        .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(200));
  }

  private static void assertOperational(org.springframework.test.web.servlet.MvcResult result) {
    assertThat(result.getResponse().getStatus()).isIn(200, 503);
  }
}
