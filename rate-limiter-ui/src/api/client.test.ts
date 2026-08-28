import { http, HttpResponse } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  acquirePermit,
  classifyDecision,
  fetchHealth,
  fetchPolicies,
  protocolWarnings,
} from "./client";
import { decision, tokenBucketPolicy } from "../test/fixtures";
import { server } from "../test/server";

const acquisitionPath = "/api/v1/rate-limits/check";

afterEach(() => {
  vi.useRealTimers();
});

describe("idempotent API reads", () => {
  it("loads policies with the exact safe GET request and optional API key", async () => {
    let calls = 0;
    server.use(
      http.get("*/api/v1/policies", ({ request }) => {
        calls += 1;
        expect(request.headers.get("Accept")).toBe("application/json");
        expect(request.headers.get("X-API-Key")).toBe("session-secret");
        expect(request.cache).toBe("no-store");
        return HttpResponse.json([tokenBucketPolicy]);
      }),
    );

    await expect(fetchPolicies("session-secret")).resolves.toEqual([
      tokenBucketPolicy,
    ]);
    expect(calls).toBe(1);
  });

  it("rejects failed and malformed policy responses without fixtures", async () => {
    server.use(
      http.get("*/api/v1/policies", () =>
        HttpResponse.json({ title: "Unavailable" }, { status: 503 }),
      ),
    );
    await expect(fetchPolicies()).rejects.toThrow("HTTP 503");

    server.use(
      http.get("*/api/v1/policies", () =>
        HttpResponse.json([{ id: "incomplete" }]),
      ),
    );
    await expect(fetchPolicies()).rejects.toThrow();
  });

  it.each(["liveness", "readiness"] as const)(
    "parses %s health truthfully",
    async (kind) => {
      server.use(
        http.get(`*/actuator/health/${kind}`, ({ request }) => {
          expect(request.headers.get("X-API-Key")).toBeNull();
          return HttpResponse.json({ status: "UP" });
        }),
      );

      const result = await fetchHealth(kind);
      expect(result.status).toBe("UP");
      expect(result.checkedAtEpochMs).toBeGreaterThan(0);
    },
  );

  it("rejects non-success and malformed health payloads", async () => {
    server.use(
      http.get("*/actuator/health/readiness", () =>
        HttpResponse.json({ status: "DOWN" }, { status: 503 }),
      ),
    );
    await expect(fetchHealth("readiness")).rejects.toThrow(
      "readiness request failed",
    );

    server.use(
      http.get("*/actuator/health/liveness", () =>
        HttpResponse.json({ state: "UP" }),
      ),
    );
    await expect(fetchHealth("liveness")).rejects.toThrow(
      "liveness request failed",
    );
  });
});

describe("non-idempotent acquisition transport", () => {
  it("sends exactly one POST with the exact body and opt-in headers", async () => {
    let calls = 0;
    server.use(
      http.post(`*${acquisitionPath}`, async ({ request }) => {
        calls += 1;
        const body: unknown = await request.json();
        expect(body).toEqual({
          policyId: "api-standard",
          key: "user:123",
          permits: 2,
        });
        expect(request.headers.get("Accept")).toBe(
          "application/json, application/problem+json",
        );
        expect(request.headers.get("Content-Type")).toBe("application/json");
        expect(request.headers.get("X-Request-Id")).toBe("client-request-1");
        expect(request.headers.get("X-API-Key")).toBe("memory-only-secret");
        expect(request.cache).toBe("no-store");
        return HttpResponse.json(
          decision({ grantedPermits: 2, remaining: 98 }),
          {
            headers: {
              "X-RateLimit-Limit": "100",
              "X-RateLimit-Remaining": "98",
              "X-RateLimit-Reset": "1800000000",
              "X-RateLimit-Source": "REDIS",
              "X-Request-Id": "request-1",
              "Cache-Control": "no-store",
            },
          },
        );
      }),
    );

    const result = await acquirePermit({
      policyId: "api-standard",
      key: "user:123",
      permits: 2,
      requestId: "client-request-1",
      apiKey: "memory-only-secret",
      sequence: 7,
    });

    expect(calls).toBe(1);
    expect(result).toMatchObject({
      policyId: "api-standard",
      requestedPermits: 2,
      httpStatus: 200,
      classification: "NORMAL_ALLOWED",
      sequence: 7,
      protocolWarnings: [],
    });
    expect(result.headers).toMatchObject({
      "Cache-Control": "no-store",
      "X-RateLimit-Source": "REDIS",
    });
    expect(result).not.toHaveProperty("key");
    expect(result).not.toHaveProperty("apiKey");
  });

  it.each([
    {
      label: "normal Redis allow",
      status: 200,
      payload: decision(),
      classification: "NORMAL_ALLOWED",
    },
    {
      label: "local charged-lease allow",
      status: 200,
      payload: decision({ source: "LOCAL_LEASE", approximate: true }),
      classification: "NORMAL_ALLOWED",
    },
    {
      label: "quota denial",
      status: 429,
      payload: decision({
        allowed: false,
        grantedPermits: 0,
        remaining: 0,
        retryAfterMs: 12_000,
        reason: "LIMIT_EXCEEDED",
      }),
      classification: "QUOTA_DENIED",
    },
    {
      label: "degraded fail-open allow",
      status: 200,
      payload: decision({
        remaining: -1,
        resetAtEpochMs: null,
        source: "FAIL_OPEN",
        reason: "BACKEND_UNAVAILABLE_FAIL_OPEN",
        degraded: true,
        approximate: true,
      }),
      classification: "DEGRADED_ALLOWED",
    },
    {
      label: "fail-closed backend denial",
      status: 503,
      payload: decision({
        allowed: false,
        remaining: -1,
        grantedPermits: 0,
        retryAfterMs: -1,
        resetAtEpochMs: null,
        source: "FAIL_CLOSED",
        reason: "BACKEND_UNAVAILABLE_FAIL_CLOSED",
        degraded: true,
      }),
      classification: "BACKEND_DENIED",
    },
  ] as const)(
    "classifies $label from a validated decision body",
    async ({ status, payload, classification }) => {
      server.use(
        http.post(`*${acquisitionPath}`, () =>
          HttpResponse.json(payload, {
            status,
            headers: { "X-Request-Id": payload.requestId },
          }),
        ),
      );

      const result = await acquirePermit({
        policyId: payload.policyId,
        key: "opaque",
        permits: 1,
      });

      expect(result.classification).toBe(classification);
      expect(result.decision).toEqual(payload);
      expect(result.httpStatus).toBe(status);
    },
  );

  it("preserves a valid RFC problem response and never retries the POST", async () => {
    let calls = 0;
    server.use(
      http.post(`*${acquisitionPath}`, () => {
        calls += 1;
        return HttpResponse.json(
          {
            type: "about:blank",
            title: "Invalid request",
            status: 400,
            detail: "Request validation failed",
            instance: acquisitionPath,
            requestId: "problem-request",
            errors: [{ field: "key", message: "must not be blank" }],
          },
          {
            status: 400,
            headers: { "Content-Type": "application/problem+json" },
          },
        );
      }),
    );

    const result = await acquirePermit({
      policyId: "api-standard",
      key: "opaque",
      permits: 1,
    });

    expect(calls).toBe(1);
    expect(result.classification).toBe("CLIENT_ERROR");
    expect(result.problem).toMatchObject({
      title: "Invalid request",
      requestId: "problem-request",
      errors: [{ field: "key", message: "must not be blank" }],
    });
  });

  it.each([408, 502, 504])(
    "classifies HTTP %i as an unknown outcome without retry",
    async (status) => {
      let calls = 0;
      server.use(
        http.post(`*${acquisitionPath}`, () => {
          calls += 1;
          return HttpResponse.text("<html>proxy failure</html>", {
            status,
            headers: { "Content-Type": "text/html" },
          });
        }),
      );

      const result = await acquirePermit({
        policyId: "api-standard",
        key: "opaque",
        permits: 1,
      });

      expect(calls).toBe(1);
      expect(result).toMatchObject({
        classification: "UNKNOWN_OUTCOME",
        httpStatus: status,
      });
      expect(result.rawJson).toBeUndefined();
      expect(result.problem).toBeUndefined();
    },
  );

  it("sanitizes a non-JSON server failure instead of exposing proxy HTML", async () => {
    server.use(
      http.post(`*${acquisitionPath}`, () =>
        HttpResponse.text("<html><pre>private upstream details</pre></html>", {
          status: 500,
          headers: { "Content-Type": "text/html" },
        }),
      ),
    );

    const result = await acquirePermit({
      policyId: "api-standard",
      key: "opaque",
      permits: 1,
    });

    expect(result.classification).toBe("SERVER_ERROR");
    expect(result.rawJson).toBeUndefined();
    expect(JSON.stringify(result)).not.toContain("private upstream details");
  });

  it("treats malformed and semantically inconsistent decision bodies as protocol errors", async () => {
    server.use(
      http.post(`*${acquisitionPath}`, () =>
        HttpResponse.json({ allowed: true }, { status: 200 }),
      ),
    );
    await expect(
      acquirePermit({ policyId: "api-standard", key: "opaque", permits: 1 }),
    ).resolves.toMatchObject({ classification: "PROTOCOL_ERROR" });

    server.use(
      http.post(`*${acquisitionPath}`, () =>
        HttpResponse.json(decision({ allowed: false, grantedPermits: 0 }), {
          status: 200,
        }),
      ),
    );
    await expect(
      acquirePermit({ policyId: "api-standard", key: "opaque", permits: 1 }),
    ).resolves.toMatchObject({ classification: "PROTOCOL_ERROR" });
  });

  it("returns unknown after a network error with exactly one attempt", async () => {
    let calls = 0;
    server.use(
      http.post(`*${acquisitionPath}`, () => {
        calls += 1;
        return HttpResponse.error();
      }),
    );

    const result = await acquirePermit({
      policyId: "api-standard",
      key: "opaque",
      permits: 1,
    });

    expect(calls).toBe(1);
    expect(result.classification).toBe("UNKNOWN_OUTCOME");
    expect(result.httpStatus).toBeNull();
  });

  it("aborts once at the finite timeout and records an unknown outcome", async () => {
    vi.useFakeTimers();
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(
      (_input, init) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () => {
            reject(new DOMException("Aborted", "AbortError"));
          });
        }),
    );

    const pending = acquirePermit({
      policyId: "api-standard",
      key: "opaque",
      permits: 1,
    });
    await vi.advanceTimersByTimeAsync(5_000);

    await expect(pending).resolves.toMatchObject({
      classification: "UNKNOWN_OUTCOME",
      httpStatus: null,
    });
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });
});

describe("decision protocol semantics", () => {
  it("recognizes only valid status/body combinations", () => {
    expect(classifyDecision(200, decision())).toBe("NORMAL_ALLOWED");
    expect(
      classifyDecision(
        200,
        decision({ degraded: true, source: "FAIL_OPEN", approximate: true }),
      ),
    ).toBe("DEGRADED_ALLOWED");
    expect(
      classifyDecision(429, decision({ allowed: false, grantedPermits: 0 })),
    ).toBe("QUOTA_DENIED");
    expect(
      classifyDecision(
        503,
        decision({ allowed: false, grantedPermits: 0, source: "FAIL_CLOSED" }),
      ),
    ).toBe("BACKEND_DENIED");
    expect(
      classifyDecision(200, decision({ allowed: false, grantedPermits: 0 })),
    ).toBe("PROTOCOL_ERROR");
    expect(classifyDecision(429, decision())).toBe("PROTOCOL_ERROR");
    expect(
      classifyDecision(503, decision({ allowed: false, source: "REDIS" })),
    ).toBe("PROTOCOL_ERROR");
  });

  it("warns for body/header disagreements and handles duplicated request-ID headers", () => {
    const payload = decision();
    expect(
      protocolWarnings(payload, {
        "X-RateLimit-Limit": "99",
        "X-RateLimit-Remaining": "98",
        "X-RateLimit-Source": "LOCAL_LEASE",
        "X-Request-Id": "different",
        "X-RateLimit-Reset": "1799999999",
      }),
    ).toEqual([
      "Response body and header disagree about limit.",
      "Response body and header disagree about remaining.",
      "Response body and header disagree about source.",
      "Response body and header disagree about request ID.",
      "Response body milliseconds and X-RateLimit-Reset epoch seconds disagree.",
    ]);

    expect(
      protocolWarnings(payload, {
        "X-RateLimit-Limit": "100",
        "X-RateLimit-Remaining": "99",
        "X-RateLimit-Source": "REDIS",
        "X-Request-Id": "request-1, request-1",
        "X-RateLimit-Reset": "1800000000",
      }),
    ).toEqual([]);
  });
});
