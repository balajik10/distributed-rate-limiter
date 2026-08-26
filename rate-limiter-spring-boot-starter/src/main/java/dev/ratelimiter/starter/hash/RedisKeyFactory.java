package dev.ratelimiter.starter.hash;

import java.util.List;
import java.util.regex.Pattern;

public final class RedisKeyFactory {
  private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9._-]{1,32}");
  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
  private final String prefix;

  public RedisKeyFactory(String prefix) {
    if (prefix == null || !PREFIX.matcher(prefix).matches()) {
      throw new IllegalArgumentException("rate-limiter.key-prefix must match " + PREFIX.pattern());
    }
    this.prefix = prefix;
  }

  public String tokenBucket(String digest) {
    return base(digest) + ":tb";
  }

  public List<String> slidingWindowLog(String digest) {
    String base = base(digest) + ":swl";
    return List.of(base + ":events", base + ":meta");
  }

  public String slidingWindowCounter(String digest) {
    return base(digest) + ":swc";
  }

  private String base(String digest) {
    if (digest == null || !DIGEST.matcher(digest).matches()) {
      throw new IllegalArgumentException("subject digest must be a full lowercase SHA-256 digest");
    }
    return prefix + ":{" + digest + "}";
  }
}
