import { describe, expect, it } from "vitest";

import {
  parsePolicies,
  policyPermitMaximum,
  requestIdSchema,
  singleCheckSchema,
  trafficConfigSchema,
} from "./contracts";
import { slidingLogPolicy, tokenBucketPolicy } from "../test/fixtures";

describe("API contract validation", () => {
  it("accepts the boundary values from the HTTP request contract", () => {
    expect(
      singleCheckSchema.parse({
        policyId: "p",
        key: "k",
        permits: 1,
        requestId: "",
      }),
    ).toMatchObject({ permits: 1 });
    expect(
      singleCheckSchema.parse({
        policyId: `p${"a".repeat(63)}`,
        key: "k".repeat(256),
        permits: 100,
        requestId: "r".repeat(64),
      }),
    ).toMatchObject({ permits: 100 });
  });

  it.each([
    [
      "empty logical key",
      { policyId: "api-standard", key: " ", permits: 1, requestId: "" },
    ],
    [
      "oversized logical key",
      {
        policyId: "api-standard",
        key: "k".repeat(257),
        permits: 1,
        requestId: "",
      },
    ],
    [
      "zero permits",
      { policyId: "api-standard", key: "key", permits: 0, requestId: "" },
    ],
    [
      "fractional permits",
      { policyId: "api-standard", key: "key", permits: 1.5, requestId: "" },
    ],
    [
      "too many permits",
      { policyId: "api-standard", key: "key", permits: 101, requestId: "" },
    ],
    [
      "invalid policy ID",
      { policyId: "UPPER CASE", key: "key", permits: 1, requestId: "" },
    ],
  ])("rejects %s", (_label, value) => {
    expect(singleCheckSchema.safeParse(value).success).toBe(false);
  });

  it("validates optional request IDs with the backend's exact alphabet and length", () => {
    expect(requestIdSchema.safeParse("trace:one_2.alpha-beta").success).toBe(
      true,
    );
    expect(requestIdSchema.safeParse("").success).toBe(true);
    expect(requestIdSchema.safeParse("has a space").success).toBe(false);
    expect(requestIdSchema.safeParse("x".repeat(65)).success).toBe(false);
  });

  it("enforces every traffic-lab safety boundary", () => {
    const valid = {
      policyId: "api-standard",
      permits: 1,
      totalRequests: 200,
      targetRps: 20,
      concurrency: 5,
      keyMode: "FIXED",
      baseKey: "demo-key",
    };
    expect(trafficConfigSchema.safeParse(valid).success).toBe(true);
    expect(
      trafficConfigSchema.safeParse({ ...valid, totalRequests: 0 }).success,
    ).toBe(false);
    expect(
      trafficConfigSchema.safeParse({ ...valid, totalRequests: 201 }).success,
    ).toBe(false);
    expect(
      trafficConfigSchema.safeParse({ ...valid, targetRps: 0 }).success,
    ).toBe(false);
    expect(
      trafficConfigSchema.safeParse({ ...valid, targetRps: 21 }).success,
    ).toBe(false);
    expect(
      trafficConfigSchema.safeParse({ ...valid, concurrency: 0 }).success,
    ).toBe(false);
    expect(
      trafficConfigSchema.safeParse({ ...valid, concurrency: 6 }).success,
    ).toBe(false);
    expect(
      trafficConfigSchema.safeParse({ ...valid, keyMode: "SHARED" }).success,
    ).toBe(false);
  });

  it("rejects runs whose estimated duration exceeds sixty seconds", () => {
    const result = trafficConfigSchema.safeParse({
      policyId: "api-standard",
      permits: 1,
      totalRequests: 61,
      targetRps: 1,
      concurrency: 1,
      keyMode: "FIXED",
      baseKey: "demo-key",
    });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0]).toMatchObject({
        path: ["totalRequests"],
        message: "Estimated duration must be 60 seconds or less",
      });
    }
  });

  it("parses real policy shapes and rejects malformed or unsafe metadata", () => {
    expect(parsePolicies([tokenBucketPolicy, slidingLogPolicy])).toHaveLength(
      2,
    );
    expect(() =>
      parsePolicies([{ ...tokenBucketPolicy, limit: -1 }]),
    ).toThrow();
    expect(() =>
      parsePolicies([{ ...tokenBucketPolicy, localCache: { enabled: true } }]),
    ).toThrow();
    expect(() => parsePolicies("not-an-array")).toThrow();
  });

  it("caps permits by both the HTTP maximum and the selected policy", () => {
    const tokenBucket = tokenBucketPolicy.tokenBucket;
    if (!tokenBucket)
      throw new Error("Fixture must contain token-bucket metadata");
    expect(policyPermitMaximum(tokenBucketPolicy)).toBe(100);
    expect(policyPermitMaximum(slidingLogPolicy)).toBe(5);
    expect(
      policyPermitMaximum({
        ...tokenBucketPolicy,
        limit: 1_000,
        tokenBucket: { ...tokenBucket, capacity: 500 },
      }),
    ).toBe(100);
  });
});
