package dev.ratelimiter.starter.backend;

import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

public final class RedisAvailabilityClassifier {
  public Optional<BackendUnavailableException.Category> classify(Throwable failure) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable current = failure;
        current != null && seen.add(current);
        current = current.getCause()) {
      if (current instanceof RedisCommandTimeoutException
          || current instanceof QueryTimeoutException) {
        return Optional.of(BackendUnavailableException.Category.AMBIGUOUS_EXECUTION);
      }
      if (current instanceof RedisConnectionFailureException
          || current instanceof RedisConnectionException) {
        return Optional.of(BackendUnavailableException.Category.CONNECTION);
      }
    }
    return Optional.empty();
  }
}
