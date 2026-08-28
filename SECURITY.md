# Security policy

Please report vulnerabilities privately through GitHub's security advisory interface. Do not open a public issue containing exploit details, credentials, customer identifiers, or production topology.

Version `1.x` receives security fixes. Maintainers aim to acknowledge a report within five business days; this is a best-effort open-source project, not an SLA.

Production operators must use Redis ACL authentication and TLS on a private network, keep `maxmemory-policy noeviction`, enable suitable persistence/replication/backups, and source API/HMAC/Redis secrets from a secret manager. Rotate the key-hash HMAC secret only through a coordinated blue/green cutover because a changed digest creates an independent quota namespace.

The HTTP service belongs behind trusted service-to-service authentication or an API gateway with a coarse ingress limit. Never expose Redis, Prometheus, or Grafana directly to an untrusted network.

The Operations Console is local developer/demo tooling, not a public production administration system. Its optional API key exists only in browser memory; never add a credential to Vite variables, Docker build arguments, Nginx configuration, URLs, storage, logs, screenshots, or test artifacts. A public deployment requires HTTPS plus real end-user authentication and authorization at a trusted reverse proxy. Do not enable wildcard CORS or expose Docker DNS/Redis to make the browser integration work.

Acquisition requests are non-idempotent. React, proxies, service workers, test helpers, and infrastructure must not retry a timed-out `POST /api/v1/rate-limits/check`; the Redis operation may already have consumed permits. Report any path that persists/logs logical keys, replays acquisitions, injects credentials, bypasses the read-only policy model, or converts backend `429`/`503` decisions into unrelated HTML as a security or correctness issue.
