package dev.ratelimiter.core;

/** Stable, machine-readable reason for a rate-limit decision. */
public enum DecisionReason {
  ALLOWED,
  LIMIT_EXCEEDED,
  BACKEND_UNAVAILABLE_FAIL_OPEN,
  BACKEND_UNAVAILABLE_FAIL_CLOSED
}
