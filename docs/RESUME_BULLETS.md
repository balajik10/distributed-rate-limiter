# Resume bullets

- Built a Java 21/Maven multi-module distributed rate limiter with a Spring-independent public API, reusable Spring Boot auto-configuration, and a Spring MVC service backed by trusted, versioned YAML policies.
- Implemented token bucket, exact sliding-window log, and interpolated sliding-window counter state machines in handwritten atomic Redis Lua, using server time, integer-safe arithmetic, TTL-bound state, and SHA-256/HMAC Redis Cluster hash tags.
- Designed a Caffeine charge-ahead leasing path with atomic monotonic expiry, striped single-flight refill, adaptive batches, per-policy fail-open/fail-closed handling, Micrometer observability, and a documented `M(B-1)` cache timing-error bound.
- Built a strict-TypeScript React operations/demo console from generated OpenAPI types, with real policy inspection, non-retrying decision diagnostics, bounded browser traffic demonstrations, truthful degraded/ambiguous states, and WCAG-oriented responsive interaction.
- Packaged the console in a hardened non-root Nginx image with same-origin proxying and isolated Docker networking, preserving backend CORS and non-idempotent `429`/`503`/gateway semantics.
- Extended SHA-pinned GitHub Actions with frontend quality/coverage, Java and JavaScript/TypeScript CodeQL, and isolated real Redis/Spring/Nginx Playwright accessibility tests alongside Maven/Testcontainers verification.

These bullets describe a local operations and interview console in a production-style repository. They do not claim a public production deployment, global scheduling fairness, perfect accuracy, verified 100k RPS, or invented latency/coverage results.
