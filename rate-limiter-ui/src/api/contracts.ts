import { z } from "zod";
import type { components } from "./generated";

export type GeneratedPolicy = Omit<
  components["schemas"]["PolicyView"],
  "slidingWindow" | "tokenBucket"
> & {
  slidingWindow?: components["schemas"]["SlidingWindowView"] | null | undefined;
  tokenBucket?: components["schemas"]["TokenBucketView"] | null | undefined;
};
export type GeneratedDecision = Omit<
  components["schemas"]["RateLimitCheckResponse"],
  "resetAtEpochMs"
> & {
  resetAtEpochMs?: number | null;
};

export const algorithmSchema = z.enum([
  "TOKEN_BUCKET",
  "SLIDING_WINDOW_LOG",
  "SLIDING_WINDOW_COUNTER",
]);
export const failureModeSchema = z.enum(["FAIL_OPEN", "FAIL_CLOSED"]);
export const decisionSourceSchema = z.enum([
  "REDIS",
  "LOCAL_LEASE",
  "FAIL_OPEN",
  "FAIL_CLOSED",
]);
export const decisionReasonSchema = z.enum([
  "ALLOWED",
  "LIMIT_EXCEEDED",
  "BACKEND_UNAVAILABLE_FAIL_OPEN",
  "BACKEND_UNAVAILABLE_FAIL_CLOSED",
]);

const localCacheSchema = z.object({
  enabled: z.boolean(),
  maxLeaseSize: z.number().int().nonnegative(),
  maxLeaseTtlMs: z.number().int().nonnegative(),
  expectedMaxInstances: z.number().int().positive(),
  maxErrorPermits: z.number().int().nonnegative(),
});

export const policySchema = z.object({
  id: z.string().min(1).max(64),
  version: z.number().int().positive(),
  algorithm: algorithmSchema,
  failureMode: failureModeSchema,
  limit: z.number().int().positive(),
  tokenBucket: z
    .object({
      capacity: z.number().int().positive(),
      refillTokens: z.number().int().positive(),
      refillPeriodMs: z.number().int().positive(),
    })
    .nullish(),
  slidingWindow: z
    .object({
      limit: z.number().int().positive(),
      windowMs: z.number().int().positive(),
    })
    .nullish(),
  localCache: localCacheSchema,
});

export const decisionSchema = z.object({
  allowed: z.boolean(),
  policyId: z.string().min(1),
  policyVersion: z.number().int().positive(),
  algorithm: algorithmSchema,
  limit: z.number().int().positive(),
  remaining: z.number().int(),
  grantedPermits: z.number().int().nonnegative(),
  retryAfterMs: z.number().int(),
  resetAtEpochMs: z.number().int().nullable(),
  source: decisionSourceSchema,
  reason: decisionReasonSchema,
  approximate: z.boolean(),
  degraded: z.boolean(),
  requestId: z.string().min(1),
});

export const problemSchema = z.object({
  type: z.string().optional(),
  title: z.string().optional(),
  status: z.number().int().optional(),
  detail: z.string().optional(),
  instance: z.string().optional(),
  requestId: z.string().optional(),
  errors: z
    .array(z.object({ field: z.string(), message: z.string() }))
    .optional(),
});

export const requestIdSchema = z
  .string()
  .max(64, "Request ID must be at most 64 characters")
  .regex(
    /^[A-Za-z0-9._:-]*$/,
    "Use letters, numbers, period, underscore, colon, or hyphen only",
  );

export const singleCheckSchema = z.object({
  policyId: z
    .string()
    .min(1, "Choose a policy")
    .max(64)
    .regex(/^[a-z0-9][a-z0-9._-]{0,63}$/),
  key: z
    .string()
    .trim()
    .min(1, "Logical key is required")
    .max(256, "Maximum length is 256"),
  permits: z.number().int("Use a whole number").min(1).max(100),
  requestId: requestIdSchema,
});

export const trafficConfigSchema = z
  .object({
    policyId: z.string().min(1),
    permits: z.number().int().min(1).max(100),
    totalRequests: z.number().int().min(1).max(200),
    targetRps: z.number().int().min(1).max(20),
    concurrency: z.number().int().min(1).max(5),
    keyMode: z.enum(["FIXED", "UNIQUE"]),
    baseKey: z.string().trim().min(1).max(256),
  })
  .refine((value) => Math.ceil(value.totalRequests / value.targetRps) <= 60, {
    path: ["totalRequests"],
    message: "Estimated duration must be 60 seconds or less",
  })
  .refine((value) => value.keyMode === "FIXED" || value.baseKey.length <= 252, {
    path: ["baseKey"],
    message:
      "Unique-key mode reserves four characters for the request sequence",
  });

export type Algorithm = z.infer<typeof algorithmSchema>;
export type FailureMode = z.infer<typeof failureModeSchema>;
export type DecisionSource = z.infer<typeof decisionSourceSchema>;
export type Policy = z.infer<typeof policySchema>;
export type Decision = z.infer<typeof decisionSchema>;
export type ProblemDetails = z.infer<typeof problemSchema>;
export type SingleCheckValues = z.infer<typeof singleCheckSchema>;
export type TrafficConfig = z.infer<typeof trafficConfigSchema>;

export function parsePolicies(input: unknown): Policy[] {
  return z.array(policySchema).parse(input).map(adaptGeneratedPolicy);
}

export function adaptGeneratedPolicy(input: GeneratedPolicy): Policy {
  return policySchema.parse(input);
}

export function parseDecision(input: unknown): Decision | null {
  const parsed = decisionSchema.safeParse(input);
  return parsed.success ? adaptGeneratedDecision(parsed.data) : null;
}

export function adaptGeneratedDecision(input: GeneratedDecision): Decision {
  return decisionSchema.parse(input);
}

export function policyPermitMaximum(policy: Policy): number {
  return Math.min(100, policy.tokenBucket?.capacity ?? policy.limit);
}
