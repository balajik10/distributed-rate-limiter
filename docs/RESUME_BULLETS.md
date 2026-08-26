# Resume bullets

- Built a Java 21/Maven multi-module distributed rate limiter with a Spring-independent public API, reusable Spring Boot auto-configuration, and a Spring MVC service backed by trusted, versioned YAML policies.
- Implemented token bucket, exact sliding-window log, and interpolated sliding-window counter state machines in handwritten atomic Redis Lua, using server time, integer-safe arithmetic, TTL-bound state, and SHA-256/HMAC Redis Cluster hash tags.
- Designed a Caffeine charge-ahead leasing path with atomic monotonic expiry, striped single-flight refill, adaptive batches, per-policy fail-open/fail-closed handling, Micrometer observability, and a documented `M(B-1)` cache timing-error bound.
