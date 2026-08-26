package dev.ratelimiter.starter.backend;

public final class BackendUnavailableException extends RuntimeException {
  public enum Category {
    CONNECTION,
    AMBIGUOUS_EXECUTION
  }

  private final Category category;

  public BackendUnavailableException(Category category, Throwable cause) {
    super("Redis rate-limit backend is unavailable", cause);
    this.category = category;
  }

  public Category category() {
    return category;
  }
}
