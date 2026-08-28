import {
  parseDecision,
  parsePolicies,
  problemSchema,
  type Decision,
  type Policy,
  type ProblemDetails,
} from "./contracts";

const acquisitionTimeoutMs = 5_000;
const capturedHeaderNames = [
  "X-RateLimit-Limit",
  "X-RateLimit-Remaining",
  "X-RateLimit-Reset",
  "Retry-After",
  "X-RateLimit-Source",
  "X-Request-Id",
  "Cache-Control",
] as const;

export type AcquisitionClassification =
  | "NORMAL_ALLOWED"
  | "QUOTA_DENIED"
  | "DEGRADED_ALLOWED"
  | "BACKEND_DENIED"
  | "CLIENT_ERROR"
  | "SERVER_ERROR"
  | "UNKNOWN_OUTCOME"
  | "PROTOCOL_ERROR";

export type AcquisitionHeaders = Partial<
  Record<(typeof capturedHeaderNames)[number], string>
>;

export type AcquisitionRecord = {
  id: string;
  policyId: string;
  requestedPermits: number;
  startedAtEpochMs: number;
  latencyMs: number;
  httpStatus: number | null;
  classification: AcquisitionClassification;
  headers: AcquisitionHeaders;
  protocolWarnings: string[];
  decision?: Decision;
  problem?: ProblemDetails;
  rawJson?: unknown;
  sequence?: number;
  completionOrder?: number;
};

export type AcquirePermitInput = {
  policyId: string;
  key: string;
  permits: number;
  requestId?: string;
  apiKey?: string;
  sequence?: number;
};

export type HealthStatus = {
  status: string;
  checkedAtEpochMs: number;
};

export class ApiReadError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "ApiReadError";
    this.status = status;
  }
}

function withOptionalApiKey(apiKey?: string): Record<string, string> {
  return apiKey ? { "X-API-Key": apiKey } : {};
}

async function readJson(response: Response): Promise<unknown> {
  const contentType = response.headers.get("Content-Type") ?? "";
  if (!contentType.toLowerCase().includes("json")) return undefined;
  try {
    return await response.json();
  } catch {
    return undefined;
  }
}

export async function fetchPolicies(apiKey?: string): Promise<Policy[]> {
  const response = await fetch("/api/v1/policies", {
    method: "GET",
    headers: { Accept: "application/json", ...withOptionalApiKey(apiKey) },
    cache: "no-store",
  });
  if (!response.ok)
    throw new ApiReadError(
      `Policies request failed with HTTP ${response.status}`,
      response.status,
    );
  return parsePolicies(await readJson(response));
}

export async function fetchHealth(
  kind: "liveness" | "readiness",
): Promise<HealthStatus> {
  const response = await fetch(`/actuator/health/${kind}`, {
    method: "GET",
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  const payload = await readJson(response);
  if (
    !response.ok ||
    typeof payload !== "object" ||
    payload === null ||
    !("status" in payload)
  ) {
    throw new ApiReadError(`${kind} request failed`, response.status);
  }
  const status = Reflect.get(payload, "status");
  if (typeof status !== "string")
    throw new Error(`${kind} returned a malformed response`);
  return { status, checkedAtEpochMs: Date.now() };
}

function captureHeaders(headers: Headers): AcquisitionHeaders {
  return Object.fromEntries(
    capturedHeaderNames.flatMap((name) => {
      const value = headers.get(name);
      return value === null ? [] : [[name, value]];
    }),
  );
}

function firstHeaderValue(value: string | undefined): string | undefined {
  return value?.split(",", 1)[0]?.trim();
}

export function protocolWarnings(
  decision: Decision,
  headers: AcquisitionHeaders,
): string[] {
  const warnings: string[] = [];
  const pairs: Array<[string, string | number, string | undefined]> = [
    ["limit", decision.limit, headers["X-RateLimit-Limit"]],
    ["remaining", decision.remaining, headers["X-RateLimit-Remaining"]],
    ["source", decision.source, headers["X-RateLimit-Source"]],
    [
      "request ID",
      decision.requestId,
      firstHeaderValue(headers["X-Request-Id"]),
    ],
  ];
  for (const [label, bodyValue, headerValue] of pairs) {
    if (headerValue !== undefined && String(bodyValue) !== headerValue) {
      warnings.push(`Response body and header disagree about ${label}.`);
    }
  }
  if (
    decision.resetAtEpochMs !== null &&
    headers["X-RateLimit-Reset"] !== undefined
  ) {
    const headerSeconds = Number(headers["X-RateLimit-Reset"]);
    if (
      Number.isFinite(headerSeconds) &&
      Math.floor(decision.resetAtEpochMs / 1000) !== headerSeconds
    ) {
      warnings.push(
        "Response body milliseconds and X-RateLimit-Reset epoch seconds disagree.",
      );
    }
  }
  if (decision.retryAfterMs >= 0 && headers["Retry-After"] !== undefined) {
    const headerSeconds = Number(headers["Retry-After"]);
    if (
      Number.isFinite(headerSeconds) &&
      Math.ceil(decision.retryAfterMs / 1000) !== headerSeconds
    ) {
      warnings.push(
        "Response body milliseconds and Retry-After seconds disagree.",
      );
    }
  }
  return warnings;
}

export function classifyDecision(
  status: number,
  decision: Decision,
): AcquisitionClassification {
  if (status === 200 && decision.allowed && !decision.degraded)
    return "NORMAL_ALLOWED";
  if (
    status === 200 &&
    decision.allowed &&
    decision.degraded &&
    decision.source === "FAIL_OPEN"
  ) {
    return "DEGRADED_ALLOWED";
  }
  if (status === 429 && !decision.allowed) return "QUOTA_DENIED";
  if (
    status === 503 &&
    !decision.allowed &&
    decision.source === "FAIL_CLOSED"
  ) {
    return "BACKEND_DENIED";
  }
  return "PROTOCOL_ERROR";
}

function baseRecord(
  input: AcquirePermitInput,
  startedAtEpochMs: number,
  latencyMs: number,
): Omit<AcquisitionRecord, "classification"> {
  return {
    id: crypto.randomUUID(),
    policyId: input.policyId,
    requestedPermits: input.permits,
    startedAtEpochMs,
    latencyMs,
    httpStatus: null,
    headers: {},
    protocolWarnings: [],
    ...(input.sequence === undefined ? {} : { sequence: input.sequence }),
  };
}

export async function acquirePermit(
  input: AcquirePermitInput,
): Promise<AcquisitionRecord> {
  const startedEpochMs = Date.now();
  const startedPerformance = performance.now();
  const requestId = input.requestId?.length
    ? input.requestId
    : crypto.randomUUID();
  const controller = new AbortController();
  const timeout = window.setTimeout(
    () => controller.abort("timeout"),
    acquisitionTimeoutMs,
  );

  try {
    const response = await fetch("/api/v1/rate-limits/check", {
      method: "POST",
      headers: {
        Accept: "application/json, application/problem+json",
        "Content-Type": "application/json",
        "X-Request-Id": requestId,
        ...withOptionalApiKey(input.apiKey),
      },
      body: JSON.stringify({
        policyId: input.policyId,
        key: input.key,
        permits: input.permits,
      }),
      cache: "no-store",
      signal: controller.signal,
    });
    const headers = captureHeaders(response.headers);
    const rawJson = await readJson(response);
    const common = {
      id: crypto.randomUUID(),
      policyId: input.policyId,
      requestedPermits: input.permits,
      startedAtEpochMs: startedEpochMs,
      latencyMs: performance.now() - startedPerformance,
      httpStatus: response.status,
      headers,
      rawJson,
      ...(input.sequence === undefined ? {} : { sequence: input.sequence }),
    };

    if ([408, 502, 504].includes(response.status)) {
      return {
        ...common,
        classification: "UNKNOWN_OUTCOME",
        protocolWarnings: [],
      };
    }

    if ([200, 429, 503].includes(response.status)) {
      const decision = parseDecision(rawJson);
      if (decision) {
        return {
          ...common,
          classification: classifyDecision(response.status, decision),
          decision,
          protocolWarnings: protocolWarnings(decision, headers),
        };
      }
      if (response.status !== 503) {
        return {
          ...common,
          classification: "PROTOCOL_ERROR",
          protocolWarnings: [],
        };
      }
    }

    const problem = problemSchema.safeParse(rawJson);
    return {
      ...common,
      classification: response.status >= 500 ? "SERVER_ERROR" : "CLIENT_ERROR",
      protocolWarnings: [],
      ...(problem.success ? { problem: problem.data } : {}),
    };
  } catch {
    return {
      ...baseRecord(
        input,
        startedEpochMs,
        performance.now() - startedPerformance,
      ),
      classification: "UNKNOWN_OUTCOME",
    };
  } finally {
    window.clearTimeout(timeout);
  }
}
