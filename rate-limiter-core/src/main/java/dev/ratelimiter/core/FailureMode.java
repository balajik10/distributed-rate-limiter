package dev.ratelimiter.core;

/** Behavior to apply when Redis cannot provide a decision. */
public enum FailureMode {
  FAIL_OPEN,
  FAIL_CLOSED
}
