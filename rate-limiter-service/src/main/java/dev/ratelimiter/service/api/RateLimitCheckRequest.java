package dev.ratelimiter.service.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RateLimitCheckRequest(
    @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,63}") String policyId,
    @NotBlank @Size(max = 256) String key,
    @JsonProperty(defaultValue = "1") @Min(1) @Max(100) Integer permits) {

  public RateLimitCheckRequest {
    permits = permits == null ? 1 : permits;
  }
}
