package dev.ratelimiter.service.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
import dev.ratelimiter.core.FailureMode;
import dev.ratelimiter.core.LocalCacheSettings;
import dev.ratelimiter.core.PolicyProvider;
import dev.ratelimiter.core.RateLimitDecision;
import dev.ratelimiter.core.RateLimitRequest;
import dev.ratelimiter.core.TokenBucketPolicy;
import dev.ratelimiter.core.UnknownPolicyException;
import dev.ratelimiter.service.config.ServiceSecurityProperties;
import dev.ratelimiter.service.security.SecurityConfiguration;
import dev.ratelimiter.service.web.RequestIdFilter;
import dev.ratelimiter.service.web.ServletProblemWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = {RateLimitController.class, PolicyController.class},
    properties = {
      "rate-limiter-service.security.enabled=false",
      "spring.jackson.deserialization.fail-on-unknown-properties=true"
    })
@EnableConfigurationProperties(ServiceSecurityProperties.class)
@Import({SecurityConfiguration.class, RequestIdFilter.class, ServletProblemWriter.class})
class RateLimitApiWebTest {

  private static final Instant RESET_AT = Instant.parse("2030-01-01T00:00:00Z");

  @Autowired MockMvc mockMvc;

  @MockitoBean DistributedRateLimiter rateLimiter;

  @MockitoBean PolicyProvider policyProvider;

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void allowedDecisionHasStableBodyHeadersDefaultsPermitsAndNeverLogsTheLogicalKey(
      CapturedOutput output) throws Exception {
    when(rateLimiter.tryAcquire(any(RateLimitRequest.class)))
        .thenReturn(
            new RateLimitDecision(
                true,
                "api-standard",
                1,
                Algorithm.TOKEN_BUCKET,
                100,
                93,
                1,
                0,
                RESET_AT,
                DecisionSource.REDIS,
                DecisionReason.ALLOWED,
                true,
                false));

    mockMvc
        .perform(
            post("/api/v1/rate-limits/check")
                .header(RequestIdFilter.HEADER_NAME, "trace-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"policyId\":\"api-standard\",\"key\":\"user:123\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string(RequestIdFilter.HEADER_NAME, "trace-123"))
        .andExpect(
            result ->
                assertThat(result.getResponse().getHeaders(RequestIdFilter.HEADER_NAME))
                    .containsExactly("trace-123"))
        .andExpect(header().string(RateLimitController.LIMIT_HEADER, "100"))
        .andExpect(header().string(RateLimitController.REMAINING_HEADER, "93"))
        .andExpect(header().string(RateLimitController.RESET_HEADER, "1893456000"))
        .andExpect(header().string(RateLimitController.SOURCE_HEADER, "REDIS"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
        .andExpect(jsonPath("$.allowed").value(true))
        .andExpect(jsonPath("$.policyId").value("api-standard"))
        .andExpect(jsonPath("$.policyVersion").value(1))
        .andExpect(jsonPath("$.algorithm").value("TOKEN_BUCKET"))
        .andExpect(jsonPath("$.limit").value(100))
        .andExpect(jsonPath("$.remaining").value(93))
        .andExpect(jsonPath("$.grantedPermits").value(1))
        .andExpect(jsonPath("$.retryAfterMs").value(0))
        .andExpect(jsonPath("$.resetAtEpochMs").value(1893456000000L))
        .andExpect(jsonPath("$.source").value("REDIS"))
        .andExpect(jsonPath("$.reason").value("ALLOWED"))
        .andExpect(jsonPath("$.approximate").value(true))
        .andExpect(jsonPath("$.degraded").value(false))
        .andExpect(jsonPath("$.requestId").value("trace-123"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("user:123"))));

    verify(rateLimiter).tryAcquire(new RateLimitRequest("api-standard", "user:123", 1));
    assertThat(output.getAll()).doesNotContain("user:123");
  }

  @Test
  void quotaDenialMapsTo429AndRoundedRetryAfter() throws Exception {
    when(rateLimiter.tryAcquire(any(RateLimitRequest.class)))
        .thenReturn(
            new RateLimitDecision(
                false,
                "login-strict",
                1,
                Algorithm.SLIDING_WINDOW_LOG,
                5,
                0,
                0,
                1001,
                RESET_AT,
                DecisionSource.REDIS,
                DecisionReason.LIMIT_EXCEEDED,
                false,
                false));

    mockMvc
        .perform(
            post("/api/v1/rate-limits/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"policyId\":\"login-strict\",\"key\":\"user:classified\",\"permits\":1}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "2"))
        .andExpect(header().string(RateLimitController.REMAINING_HEADER, "0"))
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.grantedPermits").value(0))
        .andExpect(jsonPath("$.retryAfterMs").value(1001))
        .andExpect(jsonPath("$.reason").value("LIMIT_EXCEEDED"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("classified"))));
  }

  @Test
  void failClosedMapsTo503WithoutFabricatedResetOrRetry() throws Exception {
    when(rateLimiter.tryAcquire(any(RateLimitRequest.class)))
        .thenReturn(
            new RateLimitDecision(
                false,
                "login-strict",
                1,
                Algorithm.SLIDING_WINDOW_LOG,
                5,
                -1,
                0,
                -1,
                null,
                DecisionSource.FAIL_CLOSED,
                DecisionReason.BACKEND_UNAVAILABLE_FAIL_CLOSED,
                false,
                true));

    mockMvc
        .perform(
            post("/api/v1/rate-limits/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"policyId\":\"login-strict\",\"key\":\"user:1\",\"permits\":1}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
        .andExpect(header().doesNotExist(RateLimitController.REMAINING_HEADER))
        .andExpect(header().doesNotExist(RateLimitController.RESET_HEADER))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("\"resetAtEpochMs\":null")))
        .andExpect(jsonPath("$.remaining").value(-1))
        .andExpect(jsonPath("$.degraded").value(true));
  }

  @Test
  void failOpenMapsToAllowedDegradedResponse() throws Exception {
    when(rateLimiter.tryAcquire(any(RateLimitRequest.class)))
        .thenReturn(
            new RateLimitDecision(
                true,
                "api-standard",
                1,
                Algorithm.TOKEN_BUCKET,
                100,
                -1,
                2,
                -1,
                null,
                DecisionSource.FAIL_OPEN,
                DecisionReason.BACKEND_UNAVAILABLE_FAIL_OPEN,
                true,
                true));

    mockMvc
        .perform(
            post("/api/v1/rate-limits/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"policyId\":\"api-standard\",\"key\":\"user:1\",\"permits\":2}"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(RateLimitController.REMAINING_HEADER))
        .andExpect(jsonPath("$.allowed").value(true))
        .andExpect(jsonPath("$.remaining").value(-1))
        .andExpect(jsonPath("$.source").value("FAIL_OPEN"))
        .andExpect(jsonPath("$.degraded").value(true));
  }

  @Test
  void malformedUnknownAndInvalidFieldsReturnProblemDetails() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/rate-limits/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"policyId\":\"api-standard\",\"key\":\"user:1\",\"permits\":1,\"capacity\":999}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Malformed JSON"))
        .andExpect(jsonPath("$.requestId").exists());

    mockMvc
        .perform(
            post("/api/v1/rate-limits/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"policyId\":\"INVALID POLICY\",\"key\":\"\",\"permits\":101}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Invalid request"))
        .andExpect(jsonPath("$.errors").isArray());

    mockMvc
        .perform(
            post("/api/v1/rate-limits/check").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("The request body is not valid for this endpoint"));
  }

  @Test
  void generatedForwardedAndInvalidRequestIdsAreHandled() throws Exception {
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
                RESET_AT,
                DecisionSource.REDIS,
                DecisionReason.ALLOWED,
                false,
                false));

    mockMvc
        .perform(
            post("/api/v1/rate-limits/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"policyId\":\"api-standard\",\"key\":\"user:1\"}"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    RequestIdFilter.HEADER_NAME,
                    matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        .andExpect(
            jsonPath("$.requestId")
                .value(
                    matchesPattern(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));

    mockMvc
        .perform(
            post("/api/v1/rate-limits/check")
                .header(RequestIdFilter.HEADER_NAME, "invalid request id")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"policyId\":\"api-standard\",\"key\":\"secret-key\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid request ID"))
        .andExpect(
            header()
                .string(
                    RequestIdFilter.HEADER_NAME, org.hamcrest.Matchers.not("invalid request id")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-key"))));

    mockMvc
        .perform(
            post("/api/v1/rate-limits/check")
                .header(RequestIdFilter.HEADER_NAME, "first", "second")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"policyId\":\"api-standard\",\"key\":\"secret-key\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Invalid request ID"));
  }

  @Test
  void rejectsOversizeBodyEvenWhenTransportDoesNotSupplyContentLength() throws Exception {
    String oversizedBody = "{\"policyId\":\"api-standard\",\"key\":\"" + "x".repeat(5000) + "\"}";

    mockMvc
        .perform(
            post("/api/v1/rate-limits/check")
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                .contentType(MediaType.APPLICATION_JSON)
                .content(oversizedBody))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Request body too large"))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("xxxxx"))));
  }

  @Test
  void unknownPolicyAndInternalFailuresAreSanitized() throws Exception {
    when(rateLimiter.tryAcquire(any(RateLimitRequest.class)))
        .thenThrow(new UnknownPolicyException("missing"))
        .thenThrow(new IllegalStateException("redis://password@host logical-key=user:secret"));

    mockMvc
        .perform(
            post("/api/v1/rate-limits/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"policyId\":\"missing\",\"key\":\"user:secret\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Unknown policy"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("user:secret"))));

    mockMvc
        .perform(
            post("/api/v1/rate-limits/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"policyId\":\"api-standard\",\"key\":\"user:secret\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.detail").value("The request could not be completed"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("user:secret"))));
  }

  @Test
  void policyEndpointsReturnSortedSanitizedServerConfiguration() throws Exception {
    TokenBucketPolicy policy =
        new TokenBucketPolicy(
            "api-standard",
            3,
            100,
            100,
            Duration.ofSeconds(60),
            FailureMode.FAIL_OPEN,
            new LocalCacheSettings(true, 10, Duration.ofMillis(100), 10, 90));
    when(policyProvider.policies()).thenReturn(List.of(policy));
    when(policyProvider.requireById("api-standard")).thenReturn(policy);

    mockMvc
        .perform(get("/api/v1/policies"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("api-standard"))
        .andExpect(jsonPath("$[0].version").value(3))
        .andExpect(jsonPath("$[0].tokenBucket.capacity").value(100))
        .andExpect(jsonPath("$[0].tokenBucket.refillPeriodMs").value(60000))
        .andExpect(jsonPath("$[0].localCache.maxLeaseSize").value(10))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("redis"))));

    mockMvc
        .perform(get("/api/v1/policies/api-standard"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.failureMode").value("FAIL_OPEN"));
  }
}
