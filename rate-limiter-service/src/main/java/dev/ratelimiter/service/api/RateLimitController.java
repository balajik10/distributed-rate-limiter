package dev.ratelimiter.service.api;

import dev.ratelimiter.core.DecisionReason;
import dev.ratelimiter.core.DistributedRateLimiter;
import dev.ratelimiter.core.RateLimitDecision;
import dev.ratelimiter.core.RateLimitRequest;
import dev.ratelimiter.service.web.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rate-limits")
public class RateLimitController {

  static final String LIMIT_HEADER = "X-RateLimit-Limit";
  static final String REMAINING_HEADER = "X-RateLimit-Remaining";
  static final String RESET_HEADER = "X-RateLimit-Reset";
  static final String SOURCE_HEADER = "X-RateLimit-Source";

  private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitController.class);

  private final DistributedRateLimiter rateLimiter;

  public RateLimitController(DistributedRateLimiter rateLimiter) {
    this.rateLimiter = rateLimiter;
  }

  @PostMapping("/check")
  @Operation(
      summary = "Acquire rate-limit permits",
      description =
          "This operation is intentionally non-idempotent; do not blindly retry a timed-out check.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Request allowed, including a possible degraded fail-open decision",
        headers = {
          @Header(name = "X-RateLimit-Limit", description = "Configured policy limit"),
          @Header(name = "X-RateLimit-Remaining", description = "Known remaining permits"),
          @Header(name = "X-RateLimit-Reset", description = "Reset time in epoch seconds"),
          @Header(name = "X-RateLimit-Source", description = "Decision source"),
          @Header(name = "X-Request-Id", description = "Request correlation identifier"),
          @Header(name = "Cache-Control", description = "Decision responses are not cacheable")
        },
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = RateLimitCheckResponse.class))),
    @ApiResponse(
        responseCode = "429",
        description = "Quota denied by the selected policy",
        headers = {
          @Header(name = "X-RateLimit-Limit", description = "Configured policy limit"),
          @Header(name = "X-RateLimit-Remaining", description = "Known remaining permits"),
          @Header(name = "X-RateLimit-Reset", description = "Reset time in epoch seconds"),
          @Header(name = "X-RateLimit-Source", description = "Decision source"),
          @Header(name = "Retry-After", description = "Retry delay in whole seconds"),
          @Header(name = "X-Request-Id", description = "Request correlation identifier"),
          @Header(name = "Cache-Control", description = "Decision responses are not cacheable")
        },
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = RateLimitCheckResponse.class))),
    @ApiResponse(
        responseCode = "503",
        description = "Fail-closed policy blocked the request because Redis was unavailable",
        headers = {
          @Header(name = "X-RateLimit-Limit", description = "Configured policy limit"),
          @Header(name = "X-RateLimit-Source", description = "Decision source"),
          @Header(name = "Retry-After", description = "Retry delay when one is known"),
          @Header(name = "X-Request-Id", description = "Request correlation identifier"),
          @Header(name = "Cache-Control", description = "Decision responses are not cacheable")
        },
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = RateLimitCheckResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Invalid request",
        content =
            @Content(
                mediaType = "application/problem+json",
                schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
    @ApiResponse(
        responseCode = "401",
        description = "Missing or invalid API key when authentication is enabled",
        content = @Content(mediaType = "application/problem+json")),
    @ApiResponse(
        responseCode = "403",
        description = "Authenticated caller is not permitted",
        content = @Content(mediaType = "application/problem+json")),
    @ApiResponse(
        responseCode = "404",
        description = "Unknown policy",
        content = @Content(mediaType = "application/problem+json")),
    @ApiResponse(
        responseCode = "413",
        description = "Request body exceeds the service limit",
        content = @Content(mediaType = "application/problem+json")),
    @ApiResponse(
        responseCode = "500",
        description = "Sanitized internal service failure",
        content = @Content(mediaType = "application/problem+json"))
  })
  public ResponseEntity<RateLimitCheckResponse> check(
      @Valid @RequestBody RateLimitCheckRequest request, HttpServletRequest servletRequest) {
    long startedAt = System.nanoTime();
    RateLimitDecision decision =
        rateLimiter.tryAcquire(
            new RateLimitRequest(request.policyId(), request.key(), request.permits()));
    String requestId = RequestIdFilter.requestId(servletRequest);
    HttpStatus status = statusFor(decision);

    HttpHeaders headers = headersFor(decision, status);
    LOGGER.info(
        "rate_limit_decision requestId={} policy={} algorithm={} outcome={} source={}"
            + " durationMs={} errorCategory={}",
        requestId,
        decision.policyId(),
        decision.algorithm(),
        decision.reason(),
        decision.source(),
        Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
        decision.degraded() ? "BACKEND_UNAVAILABLE" : "NONE");
    return new ResponseEntity<>(RateLimitCheckResponse.from(decision, requestId), headers, status);
  }

  static HttpStatus statusFor(RateLimitDecision decision) {
    if (decision.allowed()) {
      return HttpStatus.OK;
    }
    if (decision.reason() == DecisionReason.LIMIT_EXCEEDED) {
      return HttpStatus.TOO_MANY_REQUESTS;
    }
    if (decision.reason() == DecisionReason.BACKEND_UNAVAILABLE_FAIL_CLOSED) {
      return HttpStatus.SERVICE_UNAVAILABLE;
    }
    throw new IllegalStateException("Unsupported denied decision");
  }

  static HttpHeaders headersFor(RateLimitDecision decision, HttpStatus status) {
    HttpHeaders headers = new HttpHeaders();
    headers.setCacheControl(CacheControl.noStore());
    headers.set(LIMIT_HEADER, Integer.toString(decision.limit()));
    headers.set(SOURCE_HEADER, decision.source().name());
    if (decision.remaining() >= 0) {
      headers.set(REMAINING_HEADER, Long.toString(decision.remaining()));
    }
    if (decision.resetAt() != null) {
      headers.set(RESET_HEADER, Long.toString(decision.resetAt().getEpochSecond()));
    }
    if ((status == HttpStatus.TOO_MANY_REQUESTS || status == HttpStatus.SERVICE_UNAVAILABLE)
        && decision.retryAfterMillis() >= 0) {
      long retryAfterSeconds = Math.max(1L, Math.ceilDiv(decision.retryAfterMillis(), 1_000L));
      headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
    }
    return headers;
  }
}
