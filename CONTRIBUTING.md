# Contributing

Use Java 21, Node 24.20.0, npm, and Docker 24 or later. Before opening a change, run the checks relevant to the files you changed; before a cross-stack pull request, run all of them:

```bash
./mvnw -B -ntp spotless:apply
./mvnw -B -ntp clean verify
docker compose config --quiet
docker compose --profile observability config --quiet
cd rate-limiter-ui
npm ci
npm run format:check
npm run lint
npm run typecheck
npm run test:coverage
npm run build
npm run api:check
cd ..
./scripts/ui-e2e.sh
git diff --check
```

Unit tests named `*Test` must not require Docker. Redis/Testcontainers tests use the `*IT` suffix. Add deterministic tests for correctness changes, especially Lua state transitions and concurrency. Do not add retry-after-timeout behavior, write-behind accounting, raw logical keys, high-cardinality metric labels, or caller-defined policies.

Frontend production code must consume the generated OpenAPI types through runtime validation and use only relative same-origin API/health URLs. Never add an automatic retry for `POST /api/v1/rate-limits/check`, a service worker/offline mutation queue, persistent logical keys or decision history, a credential in `VITE_*`/Docker/Nginx, wildcard backend CORS, or mock-backed production data. MSW must reject unhandled test requests; accessibility failures and coverage thresholds are blocking.

When the backend contract changes, run `npm run api:refresh` against the verified local service, inspect the normalized snapshot and generated types, add transport/OpenAPI tests, and then run `npm run api:verify-live` against Compose. Do not hand-edit generated types or silently accept drift.

Real browser tests use the guarded `scripts/ui-e2e.sh` runner. It owns a unique isolated Compose project, alternate ports, failure capture, and exact-project cleanup; do not point failure/outage tests at a developer's default stack. Never commit `node_modules`, `dist`, coverage, Playwright reports/results, traces, videos, HAR files, credentials, or raw request bodies.

Commits should be focused and use an imperative subject. By contributing, you agree that your work is licensed under Apache-2.0.
