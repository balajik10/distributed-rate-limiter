import { useState } from "react";
import { useLocation } from "react-router-dom";
import { usePolicies } from "../../api/queries";
import { useSession } from "../../app/session";
import { EmptyState } from "../../components/EmptyState";
import { PageHeader } from "../../components/PageHeader";
import { SingleCheck } from "./SingleCheck";
import { TrafficLab } from "../traffic-lab/TrafficLab";

type PlaygroundLocationState = {
  tab?: "single" | "traffic";
  policyId?: string;
};

export function PlaygroundPage() {
  const location = useLocation();
  const routeState = location.state as PlaygroundLocationState | null;
  const [tab, setTab] = useState<"single" | "traffic">(
    routeState?.tab ?? "single",
  );
  const { apiKey, apiKeyRevision } = useSession();
  const policies = usePolicies(apiKey, apiKeyRevision);

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="Playground"
        title="Follow one permit from browser to Redis."
        description="Run a single non-idempotent acquisition or a safe finite demonstration. The console never retries permit-consuming requests."
      />
      <div
        className="tabs"
        role="tablist"
        aria-label="Playground mode"
        onKeyDown={(event) => {
          if (["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) {
            event.preventDefault();
            const next =
              event.key === "Home" || event.key === "ArrowLeft"
                ? "single"
                : "traffic";
            setTab(next);
            document.getElementById(`${next}-tab`)?.focus();
          }
        }}
      >
        <button
          role="tab"
          type="button"
          id="single-tab"
          tabIndex={tab === "single" ? 0 : -1}
          aria-selected={tab === "single"}
          aria-controls="single-panel"
          onClick={() => setTab("single")}
        >
          Single check
        </button>
        <button
          role="tab"
          type="button"
          id="traffic-tab"
          tabIndex={tab === "traffic" ? 0 : -1}
          aria-selected={tab === "traffic"}
          aria-controls="traffic-panel"
          onClick={() => setTab("traffic")}
        >
          Demo Traffic Lab
        </button>
      </div>
      {policies.isPending ? (
        <div className="loading-panel" aria-label="Loading policies">
          <span className="skeleton-line" />
          <span className="skeleton-line" />
          <span className="skeleton-line short" />
        </div>
      ) : null}
      {policies.isError ? (
        <section className="panel inline-error">
          <div>
            <strong>Policies could not be loaded.</strong>
            <p>
              Check the service connection or session API key, then explicitly
              retry this safe GET.
            </p>
          </div>
          <button
            className="button secondary"
            type="button"
            onClick={() => void policies.refetch()}
          >
            Retry safe GET
          </button>
        </section>
      ) : null}
      {policies.data?.length === 0 ? (
        <EmptyState
          title="No policies available"
          description="The trusted server configuration returned an empty list."
        />
      ) : null}
      {policies.data?.length ? (
        <div
          role="tabpanel"
          id={tab === "single" ? "single-panel" : "traffic-panel"}
          aria-labelledby={tab === "single" ? "single-tab" : "traffic-tab"}
        >
          {tab === "single" ? (
            <SingleCheck
              policies={policies.data}
              {...(routeState?.policyId
                ? { initialPolicyId: routeState.policyId }
                : {})}
              onUnknownPolicy={() => policies.refetch()}
            />
          ) : (
            <TrafficLab policies={policies.data} />
          )}
        </div>
      ) : null}
    </div>
  );
}
