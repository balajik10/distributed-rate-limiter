import { zodResolver } from "@hookform/resolvers/zod";
import { AlertTriangle, Play, RefreshCw, Square } from "lucide-react";
import { useEffect, useMemo } from "react";
import { useForm } from "react-hook-form";
import {
  policyPermitMaximum,
  trafficConfigSchema,
  type Policy,
  type TrafficConfig,
} from "../../api/contracts";
import { percentile } from "../../app/session";
import { StatusBadge } from "../../components/StatusBadge";
import { useTrafficLab } from "./TrafficLabContext";

function freshTrafficKey(policyId: string): string {
  return `ui-lab:${policyId || "policy"}:${crypto.randomUUID()}`;
}

const presets = {
  "Strict login: 6 requests": {
    policyId: "login-strict",
    totalRequests: 6,
    targetRps: 5,
    concurrency: 1,
  },
  "Token bucket traffic": {
    policyId: "api-standard",
    totalRequests: 20,
    targetRps: 5,
    concurrency: 2,
  },
  "Search traffic": {
    policyId: "search-default",
    totalRequests: 20,
    targetRps: 5,
    concurrency: 2,
  },
} as const;

export function TrafficLab({ policies }: { policies: Policy[] }) {
  const { state, start: startRun, stop } = useTrafficLab();
  const defaultPolicy = policies[0]?.id ?? "";
  const form = useForm<TrafficConfig>({
    resolver: zodResolver(trafficConfigSchema),
    defaultValues: {
      policyId: defaultPolicy,
      permits: 1,
      totalRequests: 20,
      targetRps: 5,
      concurrency: 2,
      keyMode: "FIXED",
      baseKey: freshTrafficKey(defaultPolicy),
    },
  });
  const values = form.watch();
  const selectedPolicy = policies.find(
    (policy) => policy.id === values.policyId,
  );
  const maxPermits = selectedPolicy ? policyPermitMaximum(selectedPolicy) : 100;
  const estimatedSeconds = Math.ceil(
    (values.totalRequests || 0) / Math.max(1, values.targetRps || 1),
  );
  const busy = state.running || state.inFlight > 0;
  const runLabel = state.running
    ? "Running"
    : state.inFlight > 0
      ? "Stopping"
      : state.completed > 0 &&
          state.scheduled === state.completed &&
          state.scheduled === values.totalRequests
        ? "Complete"
        : state.scheduled > 0
          ? "Stopped"
          : "Idle";

  useEffect(() => () => stop(), [stop]);
  useEffect(() => {
    if (!form.getValues("policyId") && defaultPolicy)
      form.setValue("policyId", defaultPolicy);
  }, [defaultPolicy, form]);

  const stats = useMemo(() => {
    const records = state.records;
    const received = records.filter((record) => record.httpStatus !== null);
    const count = (classification: string) =>
      records.filter((record) => record.classification === classification)
        .length;
    return {
      normalAllowed: count("NORMAL_ALLOWED"),
      degradedAllowed: count("DEGRADED_ALLOWED"),
      quotaDenied: count("QUOTA_DENIED"),
      backendDenied: count("BACKEND_DENIED"),
      errors:
        count("CLIENT_ERROR") + count("SERVER_ERROR") + count("PROTOCOL_ERROR"),
      unknown: count("UNKNOWN_OUTCOME"),
      p50: percentile(
        received.map((record) => record.latencyMs),
        0.5,
      ),
      p95: percentile(
        received.map((record) => record.latencyMs),
        0.95,
      ),
      p99: percentile(
        received.map((record) => record.latencyMs),
        0.99,
      ),
      sources: Object.fromEntries(
        ["REDIS", "LOCAL_LEASE", "FAIL_OPEN", "FAIL_CLOSED"].map((source) => [
          source,
          records.filter((record) => record.decision?.source === source).length,
        ]),
      ),
      responses: received.length,
    };
  }, [state.records]);

  const start = form.handleSubmit((config) => {
    if (config.permits > maxPermits) {
      form.setError("permits", {
        message: `Selected policy allows at most ${maxPermits}`,
      });
      form.setFocus("permits");
      return;
    }
    startRun(config);
  });

  const applyPreset = (name: keyof typeof presets) => {
    const preset = presets[name];
    const policyId = policies.some((policy) => policy.id === preset.policyId)
      ? preset.policyId
      : defaultPolicy;
    form.reset({
      policyId,
      permits: 1,
      totalRequests: preset.totalRequests,
      targetRps: preset.targetRps,
      concurrency: preset.concurrency,
      keyMode: "FIXED",
      baseKey: freshTrafficKey(policyId),
    });
  };

  return (
    <div className="traffic-stack">
      <section className="panel form-panel" aria-labelledby="traffic-heading">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">Safe and bounded</p>
            <h2 id="traffic-heading">Demo Traffic Lab</h2>
          </div>
          <StatusBadge tone="info">
            Maximum 200 requests · 60 seconds
          </StatusBadge>
        </div>
        <p className="notice">
          <AlertTriangle aria-hidden="true" size={18} /> This is a functional
          browser demonstration, not a performance benchmark. Use the k6 harness
          for load testing.
        </p>
        <div className="preset-row" aria-label="Traffic presets">
          {Object.keys(presets).map((name) => (
            <button
              className="chip-button"
              key={name}
              type="button"
              disabled={busy}
              onClick={() => applyPreset(name as keyof typeof presets)}
            >
              {name}
            </button>
          ))}
        </div>
        <form onSubmit={(event) => void start(event)} noValidate>
          <div className="traffic-form-grid">
            <div className="field span-two">
              <label htmlFor="traffic-policy">Policy</label>
              <select
                id="traffic-policy"
                disabled={busy}
                {...form.register("policyId")}
              >
                {policies.map((policy) => (
                  <option key={policy.id} value={policy.id}>
                    {policy.id} — {policy.algorithm.replaceAll("_", " ")}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="traffic-permits">Permits / request</label>
              <input
                id="traffic-permits"
                type="number"
                min="1"
                max={maxPermits}
                disabled={busy}
                aria-invalid={Boolean(form.formState.errors.permits)}
                aria-describedby="traffic-permits-help traffic-permits-error"
                {...form.register("permits", { valueAsNumber: true })}
              />
              <small id="traffic-permits-help">
                1–{maxPermits} for the selected policy
              </small>
              {form.formState.errors.permits ? (
                <span className="field-error" id="traffic-permits-error">
                  {form.formState.errors.permits.message}
                </span>
              ) : null}
            </div>
            <div className="field">
              <label htmlFor="traffic-total">Total requests</label>
              <input
                id="traffic-total"
                type="number"
                min="1"
                max="200"
                disabled={busy}
                aria-invalid={Boolean(form.formState.errors.totalRequests)}
                aria-describedby="traffic-total-help traffic-total-error"
                {...form.register("totalRequests", { valueAsNumber: true })}
              />
              <small id="traffic-total-help">1–200 requests</small>
              {form.formState.errors.totalRequests ? (
                <span className="field-error" id="traffic-total-error">
                  {form.formState.errors.totalRequests.message}
                </span>
              ) : null}
            </div>
            <div className="field">
              <label htmlFor="traffic-rate">Target rate</label>
              <input
                id="traffic-rate"
                type="number"
                min="1"
                max="20"
                disabled={busy}
                aria-invalid={Boolean(form.formState.errors.targetRps)}
                aria-describedby="traffic-rate-help traffic-rate-error"
                {...form.register("targetRps", { valueAsNumber: true })}
              />
              <small id="traffic-rate-help">1–20 requests / second</small>
              {form.formState.errors.targetRps ? (
                <span className="field-error" id="traffic-rate-error">
                  {form.formState.errors.targetRps.message}
                </span>
              ) : null}
            </div>
            <div className="field">
              <label htmlFor="traffic-concurrency">Concurrency</label>
              <input
                id="traffic-concurrency"
                type="number"
                min="1"
                max="5"
                disabled={busy}
                aria-invalid={Boolean(form.formState.errors.concurrency)}
                aria-describedby="traffic-concurrency-help traffic-concurrency-error"
                {...form.register("concurrency", { valueAsNumber: true })}
              />
              <small id="traffic-concurrency-help">
                At most five in flight
              </small>
              {form.formState.errors.concurrency ? (
                <span className="field-error" id="traffic-concurrency-error">
                  {form.formState.errors.concurrency.message}
                </span>
              ) : null}
            </div>
          </div>
          <fieldset className="mode-fieldset" disabled={busy}>
            <legend>Logical key mode</legend>
            <label>
              <input type="radio" value="FIXED" {...form.register("keyMode")} />{" "}
              Fixed key <small>Recommended: requests share one quota.</small>
            </label>
            <label>
              <input
                type="radio"
                value="UNIQUE"
                {...form.register("keyMode")}
              />{" "}
              Unique key per request{" "}
              <small>
                Each key gets independent state and may not demonstrate denial.
              </small>
            </label>
          </fieldset>
          <div className="field">
            <label htmlFor="traffic-key">Generated demo key</label>
            <div className="input-action">
              <input
                id="traffic-key"
                disabled={busy}
                aria-invalid={Boolean(form.formState.errors.baseKey)}
                aria-describedby="traffic-key-help traffic-key-error"
                {...form.register("baseKey")}
              />
              <button
                className="button ghost"
                type="button"
                disabled={busy}
                onClick={() =>
                  form.setValue("baseKey", freshTrafficKey(values.policyId), {
                    shouldValidate: true,
                  })
                }
              >
                <RefreshCw aria-hidden="true" size={16} /> Fresh key
              </button>
            </div>
            <small id="traffic-key-help">
              Kept in memory only and never written to the session ledger.
            </small>
            {form.formState.errors.baseKey ? (
              <span className="field-error" id="traffic-key-error">
                {form.formState.errors.baseKey.message}
              </span>
            ) : null}
          </div>
          <div className="run-controls">
            <div>
              <span>Estimated duration</span>
              <strong className={estimatedSeconds > 60 ? "error-text" : ""}>
                {estimatedSeconds} seconds
              </strong>
              {estimatedSeconds > 60 ? (
                <small className="field-error">
                  Reduce total requests or increase target rate. Maximum is 60
                  seconds.
                </small>
              ) : null}
            </div>
            {state.running ? (
              <button className="button danger" type="button" onClick={stop}>
                <Square aria-hidden="true" size={16} /> Stop run
              </button>
            ) : (
              <button
                className="button primary"
                type="submit"
                disabled={busy || estimatedSeconds > 60}
              >
                <Play aria-hidden="true" size={16} /> Start bounded run
              </button>
            )}
          </div>
        </form>
      </section>

      <section className="panel" aria-labelledby="run-heading">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">Actual calls only</p>
            <h2 id="run-heading">Run progress</h2>
          </div>
          <StatusBadge
            tone={
              runLabel === "Running"
                ? "info"
                : runLabel === "Complete"
                  ? "success"
                  : runLabel === "Stopping"
                    ? "warning"
                    : "neutral"
            }
          >
            {runLabel}
          </StatusBadge>
        </div>
        <div
          className="progress-track"
          role="progressbar"
          aria-label="Traffic run progress"
          aria-valuemin={0}
          aria-valuemax={Math.max(1, values.totalRequests)}
          aria-valuenow={state.completed}
        >
          <span
            style={{
              width: `${Math.min(100, (state.completed / Math.max(1, values.totalRequests)) * 100)}%`,
            }}
          />
        </div>
        <div className="run-metrics">
          {[
            ["Scheduled", state.scheduled],
            ["Responses", stats.responses],
            ["Normal allowed", stats.normalAllowed],
            ["Degraded allowed", stats.degradedAllowed],
            ["Quota denied", stats.quotaDenied],
            ["Backend denied", stats.backendDenied],
            ["Client / server errors", stats.errors],
            ["Unknown outcomes", stats.unknown],
          ].map(([label, value]) => (
            <div key={String(label)}>
              <span>{label}</span>
              <strong>{value}</strong>
            </div>
          ))}
        </div>
        <div className="run-summary-grid">
          <div>
            <h3>Source distribution</h3>
            {Object.entries(stats.sources).map(([source, count]) => (
              <p key={source}>
                <span>{source.replaceAll("_", " ")}</span>
                <strong>{count}</strong>
              </p>
            ))}
          </div>
          <div>
            <h3>Browser-observed latency</h3>
            <p>
              <span>p50</span>
              <strong>{stats.p50.toFixed(1)} ms</strong>
            </p>
            <p>
              <span>p95</span>
              <strong>{stats.p95.toFixed(1)} ms</strong>
            </p>
            <p>
              <span>p99</span>
              <strong>{stats.p99.toFixed(1)} ms</strong>
            </p>
          </div>
        </div>
        {state.records.length ? (
          <div className="table-scroll">
            <table>
              <caption>
                Completed demo requests; submission and completion order are
                tracked separately
              </caption>
              <thead>
                <tr>
                  <th>Sequence</th>
                  <th>Completed</th>
                  <th>HTTP</th>
                  <th>Classification</th>
                  <th>Source</th>
                  <th>Client latency</th>
                </tr>
              </thead>
              <tbody>
                {state.records.map((record) => (
                  <tr key={record.id}>
                    <td>{record.sequence}</td>
                    <td>{record.completionOrder}</td>
                    <td>{record.httpStatus ?? "No response"}</td>
                    <td>{record.classification.replaceAll("_", " ")}</td>
                    <td>{record.decision?.source ?? "—"}</td>
                    <td>{record.latencyMs.toFixed(1)} ms</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="empty-copy">
            No traffic run yet. Choose a preset or configure a finite run.
          </p>
        )}
      </section>
    </div>
  );
}
