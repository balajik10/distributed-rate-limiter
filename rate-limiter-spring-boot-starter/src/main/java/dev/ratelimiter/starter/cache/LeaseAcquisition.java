package dev.ratelimiter.starter.cache;

import dev.ratelimiter.core.DecisionSource;
import dev.ratelimiter.starter.backend.BackendDecision;

public record LeaseAcquisition(
    BackendDecision backendDecision,
    DecisionSource source,
    long remaining,
    long resetAtEpochMillis,
    boolean cachedTail) {

  public boolean allowed() {
    return backendDecision == null || backendDecision.allowed();
  }

  public long retryAfterMillis() {
    return backendDecision == null ? 0 : backendDecision.retryAfterMillis();
  }
}
