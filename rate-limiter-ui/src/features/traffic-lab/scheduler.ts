import { acquirePermit, type AcquisitionRecord } from "../../api/client";
import type { TrafficConfig } from "../../api/contracts";

export type SchedulerSnapshot = {
  running: boolean;
  scheduled: number;
  completed: number;
  inFlight: number;
};

export type TrafficSchedulerOptions = {
  config: TrafficConfig;
  apiKey?: string;
  acquire?: typeof acquirePermit;
  onResult: (record: AcquisitionRecord) => void;
  onState: (snapshot: SchedulerSnapshot) => void;
};

export function shouldStopTraffic(record: AcquisitionRecord): boolean {
  return !["NORMAL_ALLOWED", "QUOTA_DENIED"].includes(record.classification);
}

export class TrafficScheduler {
  readonly #options: TrafficSchedulerOptions;
  readonly #acquire: typeof acquirePermit;
  #timer: number | null = null;
  #startedAt = 0;
  #nextSequence = 1;
  #scheduled = 0;
  #completed = 0;
  #inFlight = 0;
  #running = false;

  constructor(options: TrafficSchedulerOptions) {
    this.#options = options;
    this.#acquire = options.acquire ?? acquirePermit;
  }

  start(): void {
    if (this.#running) return;
    this.#running = true;
    this.#startedAt = performance.now();
    this.#emit();
    this.#tick();
    this.#timer = window.setInterval(() => this.#tick(), 25);
  }

  stop(): void {
    this.#running = false;
    if (this.#timer !== null) window.clearInterval(this.#timer);
    this.#timer = null;
    this.#emit();
  }

  dispose(): void {
    this.stop();
  }

  snapshot(): SchedulerSnapshot {
    return {
      running: this.#running,
      scheduled: this.#scheduled,
      completed: this.#completed,
      inFlight: this.#inFlight,
    };
  }

  #emit(): void {
    this.#options.onState(this.snapshot());
  }

  #tick(): void {
    if (!this.#running) return;
    const { config } = this.#options;
    const allowedByPace = Math.min(
      config.totalRequests,
      Math.floor(
        ((performance.now() - this.#startedAt) * config.targetRps) / 1000,
      ) + 1,
    );
    while (
      this.#nextSequence <= allowedByPace &&
      this.#nextSequence <= config.totalRequests &&
      this.#inFlight < config.concurrency
    ) {
      this.#submit(this.#nextSequence);
      this.#nextSequence += 1;
    }
    if (this.#scheduled >= config.totalRequests && this.#inFlight === 0)
      this.stop();
  }

  #submit(sequence: number): void {
    const { config, apiKey } = this.#options;
    this.#scheduled += 1;
    this.#inFlight += 1;
    this.#emit();
    const key =
      config.keyMode === "FIXED"
        ? config.baseKey
        : `${config.baseKey}:${sequence}`;
    void this.#acquire({
      policyId: config.policyId,
      key,
      permits: config.permits,
      requestId: crypto.randomUUID(),
      sequence,
      ...(apiKey ? { apiKey } : {}),
    }).then((record) => {
      this.#completed += 1;
      this.#inFlight -= 1;
      this.#options.onResult({ ...record, completionOrder: this.#completed });
      if (shouldStopTraffic(record)) this.stop();
      this.#emit();
      this.#tick();
    });
  }
}
