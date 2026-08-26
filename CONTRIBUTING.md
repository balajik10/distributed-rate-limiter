# Contributing

Use Java 21 and Docker 24 or later. Before opening a change, run:

```bash
./mvnw -B -ntp spotless:apply
./mvnw -B -ntp clean verify
docker compose config --quiet
git diff --check
```

Unit tests named `*Test` must not require Docker. Redis/Testcontainers tests use the `*IT` suffix. Add deterministic tests for correctness changes, especially Lua state transitions and concurrency. Do not add retry-after-timeout behavior, write-behind accounting, raw logical keys, high-cardinality metric labels, or caller-defined policies.

Commits should be focused and use an imperative subject. By contributing, you agree that your work is licensed under Apache-2.0.
