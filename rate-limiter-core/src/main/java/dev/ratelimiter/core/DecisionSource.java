package dev.ratelimiter.core;

/** The system component that produced a public decision. */
public enum DecisionSource {
  REDIS,
  LOCAL_LEASE,
  FAIL_OPEN,
  FAIL_CLOSED
}
