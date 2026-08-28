import { afterEach, describe, expect, it, vi } from "vitest";

import type { AcquisitionRecord } from "../../api/client";
import { record, trafficConfig } from "../../test/fixtures";
import {
  shouldStopTraffic,
  TrafficScheduler,
  type SchedulerSnapshot,
} from "./scheduler";

type Deferred = {
  promise: Promise<AcquisitionRecord>;
  resolve: (value: AcquisitionRecord) => void;
};

function deferred(): Deferred {
  let resolvePromise: ((value: AcquisitionRecord) => void) | undefined;
  const promise = new Promise<AcquisitionRecord>((resolve) => {
    resolvePromise = resolve;
  });
  return {
    promise,
    resolve: (value) => {
      resolvePromise?.(value);
    },
  };
}

async function settle(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

afterEach(() => {
  vi.useRealTimers();
});

describe("traffic stop policy", () => {
  it.each(["NORMAL_ALLOWED", "QUOTA_DENIED"] as const)(
    "continues after %s",
    (classification) => {
      expect(shouldStopTraffic(record({ classification }))).toBe(false);
    },
  );

  it.each([
    "DEGRADED_ALLOWED",
    "BACKEND_DENIED",
    "CLIENT_ERROR",
    "SERVER_ERROR",
    "UNKNOWN_OUTCOME",
    "PROTOCOL_ERROR",
  ] as const)("stops after %s", (classification) => {
    expect(shouldStopTraffic(record({ classification }))).toBe(true);
  });
});

describe("TrafficScheduler", () => {
  it("paces submissions, enforces concurrency, and records completion order separately", async () => {
    vi.useFakeTimers();
    const pending: Deferred[] = [];
    const inputs: { key: string; requestId?: string; sequence?: number }[] = [];
    const results: AcquisitionRecord[] = [];
    const states: SchedulerSnapshot[] = [];
    const acquire = vi.fn(
      (input: { key: string; requestId?: string; sequence?: number }) => {
        inputs.push(input);
        const item = deferred();
        pending.push(item);
        return item.promise;
      },
    );
    const scheduler = new TrafficScheduler({
      config: trafficConfig({
        totalRequests: 4,
        targetRps: 20,
        concurrency: 2,
      }),
      acquire,
      onResult: (result) => results.push(result),
      onState: (state) => states.push(state),
    });

    scheduler.start();
    scheduler.start();
    expect(acquire).toHaveBeenCalledTimes(1);
    expect(scheduler.snapshot()).toMatchObject({
      running: true,
      scheduled: 1,
      inFlight: 1,
    });

    await vi.advanceTimersByTimeAsync(50);
    expect(acquire).toHaveBeenCalledTimes(2);
    expect(scheduler.snapshot().inFlight).toBe(2);

    await vi.advanceTimersByTimeAsync(1_000);
    expect(acquire).toHaveBeenCalledTimes(2);

    pending[1]?.resolve(record({ id: "second", sequence: 2 }));
    await settle();
    expect(acquire).toHaveBeenCalledTimes(3);
    expect(results[0]).toMatchObject({
      id: "second",
      sequence: 2,
      completionOrder: 1,
    });

    pending[0]?.resolve(record({ id: "first", sequence: 1 }));
    await settle();
    expect(acquire).toHaveBeenCalledTimes(4);
    expect(results[1]).toMatchObject({
      id: "first",
      sequence: 1,
      completionOrder: 2,
    });

    pending[2]?.resolve(record({ id: "third", sequence: 3 }));
    pending[3]?.resolve(record({ id: "fourth", sequence: 4 }));
    await settle();

    expect(scheduler.snapshot()).toEqual({
      running: false,
      scheduled: 4,
      completed: 4,
      inFlight: 0,
    });
    expect(states.at(-1)).toEqual(scheduler.snapshot());
    expect(inputs.map(({ sequence }) => sequence)).toEqual([1, 2, 3, 4]);
    expect(new Set(inputs.map(({ requestId }) => requestId)).size).toBe(4);
    expect(
      inputs.every(({ key }) => key === "ui-demo:api-standard:fixture"),
    ).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("uses a distinct logical key per sequence only in unique-key mode", async () => {
    vi.useFakeTimers();
    const keys: string[] = [];
    const acquire = vi.fn((input: { key: string; sequence?: number }) => {
      keys.push(input.key);
      return Promise.resolve(
        record({
          id: `record-${String(input.sequence)}`,
          ...(input.sequence === undefined ? {} : { sequence: input.sequence }),
        }),
      );
    });
    const scheduler = new TrafficScheduler({
      config: trafficConfig({
        totalRequests: 3,
        keyMode: "UNIQUE",
        concurrency: 1,
      }),
      acquire,
      onResult: () => undefined,
      onState: () => undefined,
    });

    scheduler.start();
    await vi.advanceTimersByTimeAsync(200);
    await settle();

    expect(keys).toEqual([
      "ui-demo:api-standard:fixture:1",
      "ui-demo:api-standard:fixture:2",
      "ui-demo:api-standard:fixture:3",
    ]);
    expect(scheduler.snapshot()).toMatchObject({
      running: false,
      scheduled: 3,
      completed: 3,
    });
  });

  it("Stop prevents new submissions but allows already-sent requests to settle", async () => {
    vi.useFakeTimers();
    const pending = deferred();
    const results: AcquisitionRecord[] = [];
    const acquire = vi.fn(() => pending.promise);
    const scheduler = new TrafficScheduler({
      config: trafficConfig({
        totalRequests: 20,
        targetRps: 20,
        concurrency: 1,
      }),
      acquire,
      onResult: (result) => results.push(result),
      onState: () => undefined,
    });

    scheduler.start();
    expect(acquire).toHaveBeenCalledTimes(1);
    scheduler.stop();
    await vi.advanceTimersByTimeAsync(2_000);
    expect(acquire).toHaveBeenCalledTimes(1);
    expect(scheduler.snapshot()).toMatchObject({
      running: false,
      scheduled: 1,
      inFlight: 1,
    });

    pending.resolve(record({ id: "settled-after-stop" }));
    await settle();
    expect(results).toHaveLength(1);
    expect(scheduler.snapshot()).toEqual({
      running: false,
      scheduled: 1,
      completed: 1,
      inFlight: 0,
    });
    expect(vi.getTimerCount()).toBe(0);
  });

  it("stops scheduling after an unsafe outcome", async () => {
    vi.useFakeTimers();
    const acquire = vi.fn(() =>
      Promise.resolve(
        record({ classification: "UNKNOWN_OUTCOME", httpStatus: null }),
      ),
    );
    const scheduler = new TrafficScheduler({
      config: trafficConfig({
        totalRequests: 10,
        targetRps: 20,
        concurrency: 1,
      }),
      acquire,
      onResult: () => undefined,
      onState: () => undefined,
    });

    scheduler.start();
    await settle();
    await vi.advanceTimersByTimeAsync(1_000);

    expect(acquire).toHaveBeenCalledTimes(1);
    expect(scheduler.snapshot()).toEqual({
      running: false,
      scheduled: 1,
      completed: 1,
      inFlight: 0,
    });
  });

  it("dispose cleans scheduling timers without aborting in-flight work", async () => {
    vi.useFakeTimers();
    const pending = deferred();
    const results: AcquisitionRecord[] = [];
    const scheduler = new TrafficScheduler({
      config: trafficConfig({ totalRequests: 10 }),
      acquire: () => pending.promise,
      onResult: (result) => results.push(result),
      onState: () => undefined,
    });

    scheduler.start();
    scheduler.dispose();
    expect(vi.getTimerCount()).toBe(0);
    expect(scheduler.snapshot()).toMatchObject({ running: false, inFlight: 1 });

    pending.resolve(record());
    await settle();
    expect(results).toHaveLength(1);
    expect(scheduler.snapshot()).toMatchObject({
      running: false,
      completed: 1,
      inFlight: 0,
    });
  });
});
