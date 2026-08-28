import {
  AlertTriangle,
  CheckCircle2,
  Clipboard,
  ShieldAlert,
  XCircle,
} from "lucide-react";
import { useState } from "react";
import type { AcquisitionRecord } from "../../api/client";
import { StatusBadge } from "../../components/StatusBadge";

const sourceExplanations: Record<string, string> = {
  REDIS: "Decided atomically by the central Redis Lua script.",
  LOCAL_LEASE:
    "Served from a short-lived permit batch already charged in Redis.",
  FAIL_OPEN:
    "Redis was unavailable; this policy allowed the request without known quota state.",
  FAIL_CLOSED: "Redis was unavailable; this policy blocked the request.",
};

const presentation = {
  NORMAL_ALLOWED: {
    heading: "Request allowed",
    tone: "success",
    icon: CheckCircle2,
  },
  QUOTA_DENIED: {
    heading: "Rate limit reached",
    tone: "danger",
    icon: XCircle,
  },
  DEGRADED_ALLOWED: {
    heading: "Allowed — degraded fail-open",
    tone: "warning",
    icon: AlertTriangle,
  },
  BACKEND_DENIED: {
    heading: "Backend unavailable — request blocked",
    tone: "danger",
    icon: ShieldAlert,
  },
  UNKNOWN_OUTCOME: {
    heading: "Outcome unknown — do not retry blindly",
    tone: "warning",
    icon: AlertTriangle,
  },
  CLIENT_ERROR: { heading: "Request rejected", tone: "danger", icon: XCircle },
  SERVER_ERROR: { heading: "Service error", tone: "danger", icon: ShieldAlert },
  PROTOCOL_ERROR: {
    heading: "Unexpected response",
    tone: "warning",
    icon: AlertTriangle,
  },
} as const;

function formatReset(epochMs: number | null | undefined): string {
  if (epochMs === null || epochMs === undefined) return "Unavailable";
  return new Date(epochMs).toLocaleString();
}

function formatRetry(milliseconds: number | undefined): string {
  if (milliseconds === undefined || milliseconds < 0) return "Not provided";
  if (milliseconds === 0) return "Now";
  return `${Math.ceil(milliseconds / 1000)} seconds (informational only)`;
}

export function DecisionInspector({ record }: { record: AcquisitionRecord }) {
  const [copied, setCopied] = useState(false);
  const view = presentation[record.classification];
  const Icon = view.icon;
  const decision = record.decision;

  const copyRequestId = async () => {
    if (!decision?.requestId) return;
    await navigator.clipboard.writeText(decision.requestId);
    setCopied(true);
  };

  return (
    <section
      className={`decision-inspector decision-${view.tone}`}
      aria-labelledby="decision-heading"
    >
      <div className="decision-heading">
        <span className="decision-icon">
          <Icon aria-hidden="true" size={25} />
        </span>
        <div>
          <p className="eyebrow">Latest real response</p>
          <h2 id="decision-heading">{view.heading}</h2>
        </div>
        <StatusBadge tone={view.tone}>
          {record.httpStatus === null
            ? "No HTTP response"
            : `HTTP ${record.httpStatus}`}
        </StatusBadge>
      </div>

      {record.classification === "UNKNOWN_OUTCOME" ? (
        <p className="decision-callout">
          No validated decision was received. The Redis Lua operation may still
          have executed and consumed permits. This request was not retried
          automatically.
        </p>
      ) : null}
      {record.problem ? (
        <div className="decision-callout">
          <strong>{record.problem.title ?? "Request failed"}</strong>
          <span>
            {record.problem.detail ?? "The service returned an error response."}
          </span>
        </div>
      ) : null}
      {record.protocolWarnings.length ? (
        <div className="protocol-warning" role="alert">
          <strong>Protocol warning</strong>
          {record.protocolWarnings.map((warning) => (
            <span key={warning}>{warning}</span>
          ))}
        </div>
      ) : null}

      {decision ? (
        <>
          <dl className="decision-grid">
            <div>
              <dt>Policy</dt>
              <dd>
                <code>{decision.policyId}</code> · v{decision.policyVersion}
              </dd>
            </div>
            <div>
              <dt>Algorithm</dt>
              <dd>{decision.algorithm.replaceAll("_", " ")}</dd>
            </div>
            <div>
              <dt>Limit</dt>
              <dd>{decision.limit}</dd>
            </div>
            <div>
              <dt>Remaining</dt>
              <dd>
                {decision.remaining === -1 ? "Unknown" : decision.remaining}
              </dd>
            </div>
            <div>
              <dt>Requested / granted</dt>
              <dd>
                {record.requestedPermits} / {decision.grantedPermits}
              </dd>
            </div>
            <div>
              <dt>Source</dt>
              <dd>
                <StatusBadge
                  tone={
                    decision.source === "LOCAL_LEASE"
                      ? "violet"
                      : decision.source.includes("FAIL")
                        ? "warning"
                        : "info"
                  }
                >
                  {decision.source.replaceAll("_", " ")}
                </StatusBadge>
              </dd>
            </div>
            <div>
              <dt>Reason</dt>
              <dd>{decision.reason.replaceAll("_", " ")}</dd>
            </div>
            <div>
              <dt>Precision</dt>
              <dd>
                <StatusBadge
                  tone={decision.approximate ? "warning" : "success"}
                >
                  {decision.approximate ? "Approximate" : "Exact decision"}
                </StatusBadge>
              </dd>
            </div>
            <div>
              <dt>Degraded</dt>
              <dd>{decision.degraded ? "Yes" : "No"}</dd>
            </div>
            <div>
              <dt>Retry after</dt>
              <dd>{formatRetry(decision.retryAfterMs)}</dd>
            </div>
            <div>
              <dt>Reset time</dt>
              <dd>{formatReset(decision.resetAtEpochMs)}</dd>
            </div>
            <div>
              <dt>Client latency</dt>
              <dd>{record.latencyMs.toFixed(1)} ms</dd>
            </div>
          </dl>
          <p className="source-explanation">
            {sourceExplanations[decision.source]}
          </p>
          {decision.approximate ? (
            <p className="approximation-note">
              Remaining quota may be approximate because of the configured
              algorithm or a centrally charged local permit lease.
            </p>
          ) : null}
          <div className="request-id-row">
            <span>
              <small>Request ID</small>
              <code>{decision.requestId}</code>
            </span>
            <button
              className="button ghost small"
              type="button"
              onClick={() => void copyRequestId()}
            >
              <Clipboard aria-hidden="true" size={15} /> Copy
            </button>
            <span className="sr-only" aria-live="polite">
              {copied ? "Request ID copied" : ""}
            </span>
          </div>
        </>
      ) : null}

      <div className="response-meta-grid">
        {Object.entries(record.headers).map(([name, value]) => (
          <div key={name}>
            <span>{name}</span>
            <code>{value}</code>
          </div>
        ))}
      </div>
      {record.headers["X-RateLimit-Reset"] ? (
        <p className="fine-print">
          X-RateLimit-Reset uses epoch seconds; resetAtEpochMs in the JSON body
          uses epoch milliseconds.
        </p>
      ) : null}
      {record.rawJson !== undefined ? (
        <details className="raw-json">
          <summary>Escaped raw JSON</summary>
          <pre>
            <code>{JSON.stringify(record.rawJson, null, 2)}</code>
          </pre>
        </details>
      ) : null}
    </section>
  );
}
