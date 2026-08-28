import {
  Activity,
  Clipboard,
  Database,
  ExternalLink as ExternalIcon,
  HeartPulse,
  RotateCw,
  Server,
} from "lucide-react";
import { useState } from "react";
import { useHealthQueries } from "../../api/queries";
import { externalLinks } from "../../app/links";
import { ExternalLink } from "../../components/ExternalLink";
import { PageHeader } from "../../components/PageHeader";
import { StatusBadge } from "../../components/StatusBadge";

const outageCommand =
  "COMPOSE_PROJECT_NAME=distributed-rate-limiter BASE_URL=http://127.0.0.1:8080 ./scripts/demo-failure-modes.sh";

export function SystemPage() {
  const health = useHealthQueries();
  const [copied, setCopied] = useState(false);
  const probes = [
    {
      name: "Java liveness",
      detail: "The Spring process is alive.",
      query: health.liveness,
      icon: HeartPulse,
    },
    {
      name: "Backend readiness",
      detail:
        "Aggregate readiness checks passed; component details are intentionally not exposed.",
      query: health.readiness,
      icon: Database,
    },
  ];
  const tools = [
    [
      "Swagger",
      "Interactive backend API contract",
      externalLinks.swagger,
      Server,
    ],
    [
      "Raw OpenAPI",
      "Machine-readable generated contract",
      externalLinks.openApi,
      ExternalIcon,
    ],
    ["Liveness", "Exact public probe", externalLinks.liveness, HeartPulse],
    [
      "Readiness",
      "Exact public aggregate probe",
      externalLinks.readiness,
      Database,
    ],
    [
      "Prometheus",
      "Server-wide metrics and queries",
      externalLinks.prometheus,
      Activity,
    ],
    [
      "Grafana",
      "Provisioned rate-limiter dashboard",
      externalLinks.grafana,
      Activity,
    ],
  ] as const;

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="System"
        title="Know what is reachable — and what is not."
        description="Health polling pauses when this document is hidden and resumes when visible. A link is not treated as proof that an external tool is healthy."
        actions={
          <button
            className="button secondary"
            type="button"
            onClick={() =>
              void Promise.all([
                health.liveness.refetch(),
                health.readiness.refetch(),
              ])
            }
          >
            <RotateCw aria-hidden="true" size={16} /> Check now
          </button>
        }
      />
      <section className="probe-grid" aria-labelledby="probe-heading">
        <h2 id="probe-heading" className="sr-only">
          Service probes
        </h2>
        {probes.map(({ name, detail, query, icon: Icon }) => {
          const stale = Boolean(
            query.isError &&
            query.data &&
            query.errorUpdatedAt - query.dataUpdatedAt > 30_000,
          );
          let visibleStatus = "Unknown / offline";
          if (stale) visibleStatus = "Status stale";
          else if (query.isPending) visibleStatus = "Checking";
          else if (query.data) visibleStatus = query.data.status;
          return (
            <article className="probe-card" key={name}>
              <Icon aria-hidden="true" size={23} />
              <div>
                <span>{name}</span>
                <strong>{visibleStatus}</strong>
                <p>{detail}</p>
                {query.data?.checkedAtEpochMs ? (
                  <small>
                    Last successful check{" "}
                    {new Date(query.data.checkedAtEpochMs).toLocaleTimeString()}
                  </small>
                ) : null}
              </div>
              <StatusBadge
                tone={
                  !stale && query.data?.status === "UP"
                    ? "success"
                    : query.isPending
                      ? "neutral"
                      : "warning"
                }
              >
                {stale
                  ? "Stale"
                  : query.data?.status === "UP"
                    ? "UP"
                    : query.isPending
                      ? "Pending"
                      : "Unknown"}
              </StatusBadge>
            </article>
          );
        })}
      </section>
      <section aria-labelledby="tools-heading">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Browser-visible URLs</p>
            <h2 id="tools-heading">External tools</h2>
          </div>
          <p>Open in a separate tab.</p>
        </div>
        <div className="tool-grid">
          {tools.map(([name, detail, href, Icon]) => (
            <ExternalLink className="tool-card" href={href} key={name}>
              <Icon aria-hidden="true" size={21} />
              <span>
                <strong>{name}</strong>
                <small>{detail}</small>
                <code>{href}</code>
              </span>
            </ExternalLink>
          ))}
        </div>
      </section>
      <section className="panel outage-guide" aria-labelledby="outage-heading">
        <div>
          <p className="eyebrow">Read-only guide</p>
          <h2 id="outage-heading">Redis outage demonstration</h2>
          <p>
            Run these commands from the repository root in a terminal. The
            browser cannot stop containers. Use an isolated Compose project
            before a failure demo if another stack is running.
          </p>
          <ul>
            <li>
              <strong>Fail-open</strong> policies return a degraded allow with
              unknown quota state.
            </li>
            <li>
              <strong>Fail-closed</strong> policies return HTTP 503 and block
              the request.
            </li>
            <li>
              Restart Redis, wait for readiness, and use a fresh logical key.
            </li>
          </ul>
        </div>
        <div className="command-block">
          <pre>
            <code>{outageCommand}</code>
          </pre>
          <button
            className="button ghost small"
            type="button"
            onClick={() => {
              void navigator.clipboard.writeText(outageCommand);
              setCopied(true);
            }}
          >
            <Clipboard aria-hidden="true" size={15} /> Copy commands
          </button>
          <span className="sr-only" aria-live="polite">
            {copied ? "Outage commands copied" : ""}
          </span>
        </div>
      </section>
    </div>
  );
}
