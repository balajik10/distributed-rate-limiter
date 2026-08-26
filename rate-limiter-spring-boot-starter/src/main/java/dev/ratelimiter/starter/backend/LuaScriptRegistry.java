package dev.ratelimiter.starter.backend;

import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

public final class LuaScriptRegistry {
  private static final RedisScript<List> TOKEN_BUCKET = script("redis/token_bucket.lua");
  private static final RedisScript<List> SLIDING_WINDOW_LOG =
      script("redis/sliding_window_log.lua");
  private static final RedisScript<List> SLIDING_WINDOW_COUNTER =
      script("redis/sliding_window_counter.lua");

  public RedisScript<List> tokenBucket() {
    return TOKEN_BUCKET;
  }

  public RedisScript<List> slidingWindowLog() {
    return SLIDING_WINDOW_LOG;
  }

  public RedisScript<List> slidingWindowCounter() {
    return SLIDING_WINDOW_COUNTER;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static RedisScript<List> script(String path) {
    DefaultRedisScript<List> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource(path));
    script.setResultType((Class) List.class);
    return script;
  }
}
