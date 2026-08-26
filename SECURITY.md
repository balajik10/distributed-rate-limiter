# Security policy

Please report vulnerabilities privately through GitHub's security advisory interface. Do not open a public issue containing exploit details, credentials, customer identifiers, or production topology.

Version `1.x` receives security fixes. Maintainers aim to acknowledge a report within five business days; this is a best-effort open-source project, not an SLA.

Production operators must use Redis ACL authentication and TLS on a private network, keep `maxmemory-policy noeviction`, enable suitable persistence/replication/backups, and source API/HMAC/Redis secrets from a secret manager. Rotate the key-hash HMAC secret only through a coordinated blue/green cutover because a changed digest creates an independent quota namespace.

The HTTP service belongs behind trusted service-to-service authentication or an API gateway with a coarse ingress limit. Never expose Redis, Prometheus, or Grafana directly to an untrusted network.
