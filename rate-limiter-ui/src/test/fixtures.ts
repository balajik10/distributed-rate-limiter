import type { AcquisitionRecord } from "../api/client";
import type { Decision, Policy, TrafficConfig } from "../api/contracts";

export const tokenBucketPolicy: Policy = {
  id: "api-standard",
  version: 1,
  algorithm: "TOKEN_BUCKET",
  failureMode: "FAIL_OPEN",
  limit: 100,
  tokenBucket: {
    capacity: 100,
    refillTokens: 100,
    refillPeriodMs: 60_000,
  },
  localCache: {
    enabled: true,
    maxLeaseSize: 10,
    maxLeaseTtlMs: 100,
    expectedMaxInstances: 10,
    maxErrorPermits: 90,
  },
};

export const slidingLogPolicy: Policy = {
  id: "login-strict",
  version: 1,
  algorithm: "SLIDING_WINDOW_LOG",
  failureMode: "FAIL_CLOSED",
  limit: 5,
  slidingWindow: {
    limit: 5,
    windowMs: 60_000,
  },
  localCache: {
    enabled: false,
    maxLeaseSize: 1,
    maxLeaseTtlMs: 0,
    expectedMaxInstances: 10,
    maxErrorPermits: 0,
  },
};

export function decision(overrides: Partial<Decision> = {}): Decision {
  return {
    allowed: true,
    policyId: "api-standard",
    policyVersion: 1,
    algorithm: "TOKEN_BUCKET",
    limit: 100,
    remaining: 99,
    grantedPermits: 1,
    retryAfterMs: 0,
    resetAtEpochMs: 1_800_000_000_000,
    source: "REDIS",
    reason: "ALLOWED",
    approximate: false,
    degraded: false,
    requestId: "request-1",
    ...overrides,
  };
}

export function record(
  overrides: Partial<AcquisitionRecord> = {},
): AcquisitionRecord {
  return {
    id: "record-1",
    policyId: "api-standard",
    requestedPermits: 1,
    startedAtEpochMs: 1_800_000_000_000,
    latencyMs: 12,
    httpStatus: 200,
    classification: "NORMAL_ALLOWED",
    headers: {},
    protocolWarnings: [],
    decision: decision(),
    ...overrides,
  };
}

export function trafficConfig(
  overrides: Partial<TrafficConfig> = {},
): TrafficConfig {
  return {
    policyId: "api-standard",
    permits: 1,
    totalRequests: 4,
    targetRps: 20,
    concurrency: 2,
    keyMode: "FIXED",
    baseKey: "ui-demo:api-standard:fixture",
    ...overrides,
  };
}
