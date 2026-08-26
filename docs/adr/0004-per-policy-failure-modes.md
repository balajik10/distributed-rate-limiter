# ADR 0004: Select Redis outage behavior per policy

- Status: Accepted
- Date: 2026-08-26

## Context

Redis unavailability creates a product trade-off, not one universally correct response. Allowing requests protects availability but removes quota enforcement; rejecting protects a security or cost boundary but makes the guarded operation unavailable. The same service can protect general API reads, login attempts, and expensive writes with different risk profiles.

Timeouts add uncertainty: the non-idempotent script may have committed even when the client did not receive its result. Conversely, Lua errors, wrong key types, malformed results, and configuration defects are programming/data failures and must not be disguised as Redis outages.

## Decision

Every trusted server-defined policy declares `FAIL_OPEN` or `FAIL_CLOSED`.

- A valid local lease is consumed first because the permit was already charged before the outage.
- With no lease, only classified Lettuce connection failures and command timeouts activate fallback, including causes wrapped by Spring exceptions.
- `FAIL_OPEN` returns allowed, degraded, approximate, unknown remaining/reset, and source/reason `FAIL_OPEN` / `BACKEND_UNAVAILABLE_FAIL_OPEN`.
- `FAIL_CLOSED` returns denied, degraded, unknown remaining/reset, and source/reason `FAIL_CLOSED` / `BACKEND_UNAVAILABLE_FAIL_CLOSED`; the HTTP service maps it to 503 rather than a quota 429.
- Command timeout is categorized as ambiguous execution. The limiter does not retry and does not cache an unknown reservation.
- Wrong Redis types, Lua/runtime errors, malformed result shapes, serialization defects, invalid policies, and other programming errors propagate as internal failures and never silently fail open.

The seed general API and search policies fail open; the strict login policy fails closed. Authentication, OTP, payment, costly mutation, and abuse-control policies should normally fail closed. Fail open is reserved for operations where temporary unavailability is worse than temporary loss of quota precision.

Liveness contains only application liveness and remains up during Redis loss. Readiness includes Redis/custom backend health and goes down. Policies are loaded locally at startup, so configured fallback remains available even if Redis is already unreachable. Bounded metrics record fallback source and ambiguous/connection error categories.

## Consequences

Failure behavior is explicit, reviewable, and aligned with each operation's business risk. Callers can distinguish genuine quota denial from backend unavailability. An outage can still serve previously charged tails until their short monotonic deadlines.

Fail-open traffic can be unbounded and is excluded from lease approximation formulas. An ambiguous fail-open call may be allowed even if Redis charged it; an ambiguous fail-closed call may reject after charging. Fail closed sacrifices availability, while fail open sacrifices enforcement. Neither mode repairs quota state lost through eviction, restart, or asynchronous failover to a stale replica.

## Alternatives considered

- One global failure mode: rejected because it cannot fit both availability-oriented and security-sensitive operations.
- Catch every Redis exception and apply fallback: rejected because it hides script, schema, serialization, and programming defects.
- Automatically retry timeouts: rejected because acquisition is non-idempotent and the first execution may have committed.
- Use 429 for fail-closed outage: rejected because no quota decision was obtained; 503 accurately describes backend unavailability.
- An independent in-memory outage limiter: rejected because it cannot preserve the global limit and would create false precision.
