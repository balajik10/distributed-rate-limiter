import {
  Activity,
  ArrowRight,
  BookOpen,
  Gauge,
  PlayCircle,
  Server,
  ShieldCheck,
  type LucideIcon,
} from "lucide-react";
import { Link } from "react-router-dom";
import { useHealthQueries, usePolicies } from "../../api/queries";
import { externalLinks } from "../../app/links";
import { useSession } from "../../app/session";
import { EmptyState } from "../../components/EmptyState";
import { ExternalLink } from "../../components/ExternalLink";
import { PageHeader } from "../../components/PageHeader";
import { StatusBadge } from "../../components/StatusBadge";

export type HealthPresentation = {
  title: string;
  description: string;
  tone: "success" | "warning" | "danger" | "neutral";
};

export function deriveHealthPresentation(
  liveness: string | undefined,
  readiness: string | undefined,
  pending: boolean,
  failed: boolean,
): HealthPresentation {
  if (pending)
    return {
      title: "Checking service",
      description: "Waiting for liveness and readiness responses.",
      tone: "neutral",
    };
  if (failed) {
    if (liveness !== undefined || readiness !== undefined) {
      return {
        title: "Status stale",
        description:
          "The last health result is retained, but the latest refresh failed.",
        tone: "warning",
      };
    }
    return {
      title: "Cannot reach the service",
      description:
        "The console is online, but the Spring service did not answer.",
      tone: "danger",
    };
  }
  if (liveness === "UP" && readiness === "UP") {
    return {
      title: "Ready to enforce limits",
      description: "Java liveness and aggregate backend readiness are both UP.",
      tone: "success",
    };
  }
  if (liveness === "UP") {
    return {
      title: "Service alive; backend not ready",
      description: "Java is alive, but aggregate readiness has not passed.",
      tone: "warning",
    };
  }
  return {
    title: "Cannot reach the service",
    description: "No healthy liveness response is available.",
    tone: "danger",
  };
}

function classificationLabel(classification: string): string {
  return classification.toLowerCase().replaceAll("_", " ");
}

export function OverviewPage() {
  const { apiKey, apiKeyRevision, records, summary } = useSession();
  const policies = usePolicies(apiKey, apiKeyRevision);
  const health = useHealthQueries();
  const presentation = deriveHealthPresentation(
    health.liveness.data?.status,
    health.readiness.data?.status,
    health.liveness.isPending || health.readiness.isPending,
    health.liveness.isError || health.readiness.isError,
  );
  const observed = Math.max(1, summary.decisions);
  const sourceCounts = Object.fromEntries(
    ["REDIS", "LOCAL_LEASE", "FAIL_OPEN", "FAIL_CLOSED"].map((source) => [
      source,
      records.filter((record) => record.decision?.source === source).length,
    ]),
  );
  const sessionMetrics: Array<{
    label: string;
    value: number;
    icon: LucideIcon;
  }> = [
    { label: "Decisions", value: summary.decisions, icon: Activity },
    { label: "Allowed", value: summary.allowed, icon: ShieldCheck },
    { label: "Denied", value: summary.denied, icon: Gauge },
    { label: "Degraded", value: summary.degraded, icon: Server },
  ];
  const outcomeDistribution: Array<{
    label: string;
    count: number;
    tone: string;
  }> = [
    { label: "Allowed", count: summary.allowed, tone: "allowed" },
    { label: "Denied", count: summary.denied, tone: "denied" },
    { label: "Degraded", count: summary.degraded, tone: "degraded" },
    { label: "Unknown", count: summary.unknown, tone: "unknown" },
  ];

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="Overview"
        title="Test distributed limits. See every decision."
        description="Send real requests to the running service and inspect how Redis, local leases, and failure policy affect the result."
      />

      <section
        className={`health-hero hero-${presentation.tone}`}
        aria-labelledby="health-heading"
      >
        <div className="hero-grid" aria-hidden="true" />
        <div className="hero-icon">
          <ShieldCheck aria-hidden="true" size={28} />
        </div>
        <div>
          <p className="eyebrow">Service status</p>
          <h2 id="health-heading">{presentation.title}</h2>
          <p>{presentation.description}</p>
        </div>
        <div className="health-probes">
          <div>
            <span>Liveness</span>
            <StatusBadge
              tone={
                health.liveness.isError
                  ? "warning"
                  : health.liveness.data?.status === "UP"
                    ? "success"
                    : "neutral"
              }
            >
              {health.liveness.isError
                ? health.liveness.data
                  ? `Stale ${health.liveness.data.status}`
                  : "Unavailable"
                : (health.liveness.data?.status ?? "Unknown")}
            </StatusBadge>
          </div>
          <div>
            <span>Readiness</span>
            <StatusBadge
              tone={
                health.readiness.isError
                  ? "warning"
                  : health.readiness.data?.status === "UP"
                    ? "success"
                    : "neutral"
              }
            >
              {health.readiness.isError
                ? health.readiness.data
                  ? `Stale ${health.readiness.data.status}`
                  : "Unavailable"
                : (health.readiness.data?.status ?? "Unknown")}
            </StatusBadge>
          </div>
        </div>
      </section>

      <section aria-labelledby="session-heading">
        <div className="section-heading">
          <div>
            <p className="eyebrow">In-memory only</p>
            <h2 id="session-heading">This browser session</h2>
          </div>
          <p>Observed by this tab, not global service metrics.</p>
        </div>
        <div className="metric-grid">
          {sessionMetrics.map(({ label, value, icon: Icon }) => (
            <article className="metric-card" key={label}>
              <Icon aria-hidden="true" size={19} />
              <span>{label}</span>
              <strong>{value}</strong>
            </article>
          ))}
        </div>
      </section>

      <div className="content-grid two-thirds">
        <section className="panel" aria-labelledby="recent-heading">
          <div className="panel-heading">
            <div>
              <p className="eyebrow">Session ledger</p>
              <h2 id="recent-heading">Recent decisions</h2>
            </div>
            <Link to="/playground">
              Run a check <ArrowRight aria-hidden="true" size={15} />
            </Link>
          </div>
          {records.length === 0 ? (
            <EmptyState
              title="No requests yet"
              description="Run a check to see the decision path."
            />
          ) : (
            <div className="table-scroll">
              <table>
                <caption className="sr-only">
                  Ten most recent rate-limit decisions from this browser tab
                </caption>
                <thead>
                  <tr>
                    <th>Time</th>
                    <th>Policy</th>
                    <th>Outcome</th>
                    <th>Source</th>
                    <th>Client latency</th>
                  </tr>
                </thead>
                <tbody>
                  {records.slice(0, 10).map((record) => (
                    <tr key={record.id}>
                      <td>
                        {new Date(record.startedAtEpochMs).toLocaleTimeString()}
                      </td>
                      <td>
                        <code>{record.policyId}</code>
                      </td>
                      <td>{classificationLabel(record.classification)}</td>
                      <td>{record.decision?.source ?? "—"}</td>
                      <td>{record.latencyMs.toFixed(1)} ms</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        <section className="panel" aria-labelledby="distribution-heading">
          <div className="panel-heading">
            <div>
              <p className="eyebrow">Browser-observed</p>
              <h2 id="distribution-heading">Outcome distribution</h2>
            </div>
          </div>
          <div
            className="distribution"
            role="img"
            aria-label={`${summary.allowed} allowed, ${summary.denied} denied, ${summary.degraded} degraded, ${summary.unknown} unknown`}
          >
            {outcomeDistribution.map(({ label, count, tone }) => (
              <div className="distribution-row" key={label}>
                <div>
                  <span>{label}</span>
                  <strong>{count}</strong>
                </div>
                <div className="bar-track">
                  <span
                    className={`bar-${tone}`}
                    style={{ width: `${(count / observed) * 100}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
          <div className="latency-summary">
            <span>
              Client latency p50{" "}
              <strong>{summary.latency.p50.toFixed(1)} ms</strong>
            </span>
            <span>
              p95 <strong>{summary.latency.p95.toFixed(1)} ms</strong>
            </span>
            <span>
              p99 <strong>{summary.latency.p99.toFixed(1)} ms</strong>
            </span>
          </div>
          <div
            className="source-summary"
            aria-label="Decision source distribution"
          >
            {Object.entries(sourceCounts).map(([source, count]) => (
              <span key={source}>
                {source.replaceAll("_", " ")} <strong>{count}</strong>
              </span>
            ))}
          </div>
        </section>
      </div>

      <section aria-labelledby="quick-heading">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Next step</p>
            <h2 id="quick-heading">Quick actions</h2>
          </div>
        </div>
        <div className="quick-grid">
          <Link className="quick-card" to="/playground">
            <PlayCircle aria-hidden="true" />
            <span>
              <strong>Run a rate-limit check</strong>
              <small>Inspect one real decision</small>
            </span>
            <ArrowRight aria-hidden="true" />
          </Link>
          <Link
            className="quick-card"
            to="/playground"
            state={{ tab: "traffic" }}
          >
            <Activity aria-hidden="true" />
            <span>
              <strong>Start a demo burst</strong>
              <small>Safe, finite browser traffic</small>
            </span>
            <ArrowRight aria-hidden="true" />
          </Link>
          <ExternalLink className="quick-card" href={externalLinks.swagger}>
            <BookOpen aria-hidden="true" />
            <span>
              <strong>Open Swagger</strong>
              <small>Explore the HTTP contract</small>
            </span>
          </ExternalLink>
          <ExternalLink className="quick-card" href={externalLinks.grafana}>
            <Gauge aria-hidden="true" />
            <span>
              <strong>Open Grafana</strong>
              <small>See server-wide metrics</small>
            </span>
          </ExternalLink>
        </div>
      </section>

      <section className="panel" aria-labelledby="policy-snapshot-heading">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">Trusted server configuration</p>
            <h2 id="policy-snapshot-heading">Policy snapshot</h2>
          </div>
          <Link to="/policies">
            Inspect policies <ArrowRight aria-hidden="true" size={15} />
          </Link>
        </div>
        {policies.isPending ? (
          <div className="skeleton-grid" aria-label="Loading policies">
            <span />
            <span />
            <span />
          </div>
        ) : null}
        {policies.isError ? (
          <div className="inline-error">
            <strong>Policies unavailable.</strong>
            <button
              className="button secondary small"
              type="button"
              onClick={() => void policies.refetch()}
            >
              Retry safe GET
            </button>
          </div>
        ) : null}
        {policies.data?.length === 0 ? (
          <EmptyState
            title="No policies returned"
            description="Trusted configuration currently contains no policies."
          />
        ) : null}
        {policies.data?.length ? (
          <div className="policy-snapshot-grid">
            {policies.data.map((policy) => (
              <article className="compact-policy" key={policy.id}>
                <code>{policy.id}</code>
                <strong>{policy.algorithm.replaceAll("_", " ")}</strong>
                <span>
                  {policy.limit} permit limit ·{" "}
                  {policy.failureMode.replace("_", " ")}
                </span>
              </article>
            ))}
          </div>
        ) : null}
      </section>
    </div>
  );
}
