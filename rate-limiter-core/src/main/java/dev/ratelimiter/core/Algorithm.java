package dev.ratelimiter.core;

/** The distributed rate-limiting algorithm selected by a policy. */
public enum Algorithm {
  TOKEN_BUCKET,
  SLIDING_WINDOW_LOG,
  SLIDING_WINDOW_COUNTER
}
