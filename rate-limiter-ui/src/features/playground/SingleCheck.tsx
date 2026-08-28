import { zodResolver } from "@hookform/resolvers/zod";
import { Clipboard, Play, RefreshCw } from "lucide-react";
import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { acquirePermit, type AcquisitionRecord } from "../../api/client";
import {
  policyPermitMaximum,
  singleCheckSchema,
  type Policy,
  type SingleCheckValues,
} from "../../api/contracts";
import { useSession } from "../../app/session";
import { DecisionInspector } from "./DecisionInspector";

function freshKey(policyId: string): string {
  return `ui-demo:${policyId || "policy"}:${crypto.randomUUID()}`;
}

function shellQuote(value: string): string {
  return `'${value.replaceAll("'", `'"'"'`)}'`;
}

export function SingleCheck({
  policies,
  initialPolicyId,
  onUnknownPolicy,
}: {
  policies: Policy[];
  initialPolicyId?: string;
  onUnknownPolicy: () => Promise<unknown>;
}) {
  const { addRecord, apiKey } = useSession();
  const [record, setRecord] = useState<AcquisitionRecord | null>(null);
  const [copyMessage, setCopyMessage] = useState("");
  const defaultPolicy =
    initialPolicyId && policies.some((policy) => policy.id === initialPolicyId)
      ? initialPolicyId
      : (policies[0]?.id ?? "");
  const form = useForm<SingleCheckValues>({
    resolver: zodResolver(singleCheckSchema),
    defaultValues: {
      policyId: defaultPolicy,
      key: freshKey(defaultPolicy),
      permits: 1,
      requestId: "",
    },
  });
  const policyId = form.watch("policyId");
  const selectedPolicy = policies.find((policy) => policy.id === policyId);
  const maxPermits = selectedPolicy ? policyPermitMaximum(selectedPolicy) : 100;

  useEffect(() => {
    if (!form.getValues("policyId") && defaultPolicy)
      form.setValue("policyId", defaultPolicy);
  }, [defaultPolicy, form]);

  const submit = form.handleSubmit(async (values) => {
    if (values.permits > maxPermits) {
      form.setError("permits", {
        message: `Selected policy allows at most ${maxPermits} permits per request`,
      });
      form.setFocus("permits");
      return;
    }
    const result = await acquirePermit({
      policyId: values.policyId,
      key: values.key,
      permits: values.permits,
      ...(values.requestId ? { requestId: values.requestId } : {}),
      ...(apiKey ? { apiKey } : {}),
    });
    setRecord(result);
    addRecord(result);
    form.setValue("requestId", "");
    const firstViolation = result.problem?.errors?.[0];
    if (
      firstViolation &&
      ["policyId", "key", "permits", "requestId"].includes(firstViolation.field)
    ) {
      const field = firstViolation.field as keyof SingleCheckValues;
      for (const violation of result.problem?.errors ?? []) {
        if (
          ["policyId", "key", "permits", "requestId"].includes(violation.field)
        ) {
          form.setError(violation.field as keyof SingleCheckValues, {
            message: violation.message,
          });
        }
      }
      form.setFocus(field);
    }
    if (result.httpStatus === 404) await onUnknownPolicy();
  });

  const copy = async (kind: "json" | "curl") => {
    const values = form.getValues();
    const body = JSON.stringify(
      { policyId: values.policyId, key: values.key, permits: values.permits },
      null,
      2,
    );
    const value =
      kind === "json"
        ? body
        : [
            `curl -i -X POST ${window.location.origin}/api/v1/rate-limits/check`,
            "  -H 'Content-Type: application/json'",
            "  -H 'X-Request-Id: <NEW_UUID>'",
            ...(apiKey ? ["  -H 'X-API-Key: YOUR_API_KEY'"] : []),
            `  --data ${shellQuote(JSON.stringify(JSON.parse(body)))}`,
          ].join(" \\\n");
    await navigator.clipboard.writeText(value);
    setCopyMessage(
      kind === "json"
        ? "Request JSON copied"
        : "cURL copied without credentials",
    );
  };

  return (
    <div className="playground-layout">
      <section
        className="panel form-panel"
        aria-labelledby="single-check-heading"
      >
        <div className="panel-heading">
          <div>
            <p className="eyebrow">One request · zero retries</p>
            <h2 id="single-check-heading">Acquire permits</h2>
          </div>
        </div>
        <form
          onSubmit={(event) => void submit(event)}
          onKeyDown={(event) => {
            if ((event.metaKey || event.ctrlKey) && event.key === "Enter")
              void submit();
          }}
          noValidate
        >
          <div className="field">
            <label htmlFor="single-policy">Policy</label>
            <select id="single-policy" {...form.register("policyId")}>
              {policies.map((policy) => (
                <option key={policy.id} value={policy.id}>
                  {policy.id} — {policy.algorithm.replaceAll("_", " ")}
                </option>
              ))}
            </select>
            <small>Trusted policy metadata loaded from the service.</small>
          </div>
          <div className="field">
            <label htmlFor="logical-key">Logical key</label>
            <div className="input-action">
              <input
                id="logical-key"
                aria-invalid={Boolean(form.formState.errors.key)}
                aria-describedby="logical-key-help logical-key-error"
                {...form.register("key")}
              />
              <button
                type="button"
                className="button ghost"
                onClick={() =>
                  form.setValue("key", freshKey(policyId), {
                    shouldValidate: true,
                  })
                }
              >
                <RefreshCw aria-hidden="true" size={16} /> Fresh key
              </button>
            </div>
            <small id="logical-key-help">
              Examples: user:123 or ip:203.0.113.5. Sent only in this request
              body; never saved in history.
            </small>
            {form.formState.errors.key ? (
              <span className="field-error" id="logical-key-error">
                {form.formState.errors.key.message}
              </span>
            ) : null}
          </div>
          <div className="form-row">
            <div className="field">
              <label htmlFor="permits">Permits</label>
              <input
                id="permits"
                type="number"
                min="1"
                max={maxPermits}
                aria-invalid={Boolean(form.formState.errors.permits)}
                aria-describedby="permits-help permits-error"
                {...form.register("permits", { valueAsNumber: true })}
              />
              <small id="permits-help">
                API maximum 100; selected-policy maximum {maxPermits}.
              </small>
              {form.formState.errors.permits ? (
                <span className="field-error" id="permits-error">
                  {form.formState.errors.permits.message}
                </span>
              ) : null}
            </div>
            <div className="field">
              <label htmlFor="client-request-id">
                Client request ID <span>optional</span>
              </label>
              <input
                id="client-request-id"
                placeholder="Generated when blank"
                autoComplete="off"
                aria-invalid={Boolean(form.formState.errors.requestId)}
                aria-describedby="request-id-help request-id-error"
                {...form.register("requestId")}
              />
              <small id="request-id-help">
                1–64 safe characters. A fresh UUID is generated when blank.
              </small>
              {form.formState.errors.requestId ? (
                <span className="field-error" id="request-id-error">
                  {form.formState.errors.requestId.message}
                </span>
              ) : null}
            </div>
          </div>
          <div className="form-actions">
            <button
              className="button primary"
              type="submit"
              disabled={form.formState.isSubmitting || policies.length === 0}
            >
              <Play aria-hidden="true" size={17} />
              {form.formState.isSubmitting ? "Checking…" : "Check limit"}
            </button>
            <button
              className="button secondary"
              type="button"
              onClick={() => void copy("json")}
            >
              <Clipboard aria-hidden="true" size={16} /> Copy request JSON
            </button>
            <button
              className="button secondary"
              type="button"
              onClick={() => void copy("curl")}
            >
              <Clipboard aria-hidden="true" size={16} /> Copy cURL
            </button>
          </div>
          <p className="shortcut-hint">
            Keyboard: Ctrl/Cmd + Enter submits exactly once.
          </p>
          <span className="sr-only" aria-live="polite">
            {copyMessage}
          </span>
        </form>
      </section>
      {record ? (
        <DecisionInspector record={record} />
      ) : (
        <section className="panel inspector-empty">
          <div>
            <Play aria-hidden="true" size={28} />
            <h2>Decision inspector</h2>
            <p>
              Submit one real request to inspect its body, rate-limit headers,
              source, precision, and client-observed latency.
            </p>
          </div>
        </section>
      )}
    </div>
  );
}
