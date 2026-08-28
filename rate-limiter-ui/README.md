# Distributed Rate Limiter Operations Console

Strict-TypeScript React/Vite console for the repository's real Spring/Redis rate limiter. Production browser calls are relative and same-origin; fixtures are test-only and acquisition POSTs are never retried automatically.

## Develop

Start the backend on `http://127.0.0.1:8080`, then:

```bash
npm ci
npm run dev
```

Open `http://localhost:5173/console`. Set `VITE_DEV_BACKEND` only when the local backend uses another origin.

## Verify

```bash
npm run format:check
npm run lint
npm run typecheck
npm run test:coverage
npm run build
npm run api:check
```

From the repository root, `./scripts/ui-e2e.sh` builds the final Nginx image and tests it against an isolated real Spring/Redis stack. The script owns and cleans only a guarded unique Compose project.

## OpenAPI synchronization

```bash
npm run api:generate
OPENAPI_BASE_URL=http://127.0.0.1:8080 npm run api:refresh
OPENAPI_BASE_URL=http://127.0.0.1:8080 npm run api:verify-live
```

`api:generate` and `api:check` use the checked-in normalized snapshot, so ordinary builds do not require a running backend. Inspect snapshot and generated-type changes together.

## Production image

The multi-stage Dockerfile uses Node 24.20.0 to build `dist`, then serves `/console` from a non-root Nginx 1.28.2 runtime on container port 8080. Nginx proxies `/api/**` and only the two UI health dependencies, preserves upstream status/body/headers, and disables upstream retry/error interception. The Compose service publishes `127.0.0.1:${UI_HOST_PORT:-3001}:8080` and joins only the app-facing `frontend` network.

See [the complete UI architecture, privacy, state mapping, accessibility, and testing guide](../docs/UI.md).
