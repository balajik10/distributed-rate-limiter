import {
  ArrowRight,
  Database,
  Filter,
  LockKeyhole,
  TimerReset,
} from "lucide-react";
import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import type { Algorithm, FailureMode } from "../../api/contracts";
import { usePolicies } from "../../api/queries";
import { useSession } from "../../app/session";
import { EmptyState } from "../../components/EmptyState";
import { PageHeader } from "../../components/PageHeader";
import { StatusBadge } from "../../components/StatusBadge";

function algorithmExplanation(algorithm: Algorithm): string {
  if (algorithm === "TOKEN_BUCKET")
    return "O(1) Redis state supports controlled bursts; it is not an exact rolling-window rule.";
  if (algorithm === "SLIDING_WINDOW_LOG")
    return "Exact rolling interval in central batch-one mode, with work and memory proportional to live events.";
  return "O(1) state deliberately approximates traffic near bucket boundaries.";
}

export function PoliciesPage() {
  const { apiKey, apiKeyRevision } = useSession();
  const policies = usePolicies(apiKey, apiKeyRevision);
  const [algorithm, setAlgorithm] = useState<Algorithm | "ALL">("ALL");
  const [failureMode, setFailureMode] = useState<FailureMode | "ALL">("ALL");
  const [cache, setCache] = useState<"ALL" | "ENABLED" | "DISABLED">("ALL");
  const filtered = useMemo(
    () =>
      (policies.data ?? []).filter(
        (policy) =>
          (algorithm === "ALL" || policy.algorithm === algorithm) &&
          (failureMode === "ALL" || policy.failureMode === failureMode) &&
          (cache === "ALL" ||
            policy.localCache.enabled === (cache === "ENABLED")),
      ),
    [algorithm, cache, failureMode, policies.data],
  );

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="Policies"
        title="Trusted policy configuration, read only."
        description="Inspect the real sanitized metadata used by the service. Callers cannot change limits through this UI."
      />
      <section className="filter-bar" aria-label="Policy filters">
        <Filter aria-hidden="true" size={18} />
        <label>
          Algorithm
          <select
            value={algorithm}
            onChange={(event) =>
              setAlgorithm(event.target.value as Algorithm | "ALL")
            }
          >
            <option value="ALL">All algorithms</option>
            <option value="TOKEN_BUCKET">Token bucket</option>
            <option value="SLIDING_WINDOW_LOG">Sliding-window log</option>
            <option value="SLIDING_WINDOW_COUNTER">
              Sliding-window counter
            </option>
          </select>
        </label>
        <label>
          Failure mode
          <select
            value={failureMode}
            onChange={(event) =>
              setFailureMode(event.target.value as FailureMode | "ALL")
            }
          >
            <option value="ALL">All modes</option>
            <option value="FAIL_OPEN">Fail open</option>
            <option value="FAIL_CLOSED">Fail closed</option>
          </select>
        </label>
        <label>
          Local leasing
          <select
            value={cache}
            onChange={(event) => setCache(event.target.value as typeof cache)}
          >
            <option value="ALL">All policies</option>
            <option value="ENABLED">Enabled</option>
            <option value="DISABLED">Disabled</option>
          </select>
        </label>
      </section>

      {policies.isPending ? (
        <div
          className="skeleton-grid"
          role="status"
          aria-label="Loading policies"
        >
          <span />
          <span />
          <span />
        </div>
      ) : null}
      {policies.isError ? (
        <section className="panel inline-error">
          <div>
            <strong>Policy metadata unavailable.</strong>
            <p>No fallback fixtures are shown.</p>
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
      {!policies.isPending && !policies.isError && filtered.length === 0 ? (
        <EmptyState
          title="No matching policies"
          description="Change one or more filters to see trusted policy metadata."
        />
      ) : null}

      <div className="policy-card-grid">
        {filtered.map((policy) => (
          <article className="policy-card" key={policy.id}>
            <div className="policy-title">
              <div>
                <code>{policy.id}</code>
                <span>Immutable version {policy.version}</span>
              </div>
              <StatusBadge
                tone={policy.failureMode === "FAIL_OPEN" ? "warning" : "danger"}
              >
                {policy.failureMode.replace("_", " ")}
              </StatusBadge>
            </div>
            <h2>{policy.algorithm.replaceAll("_", " ")}</h2>
            <p>{algorithmExplanation(policy.algorithm)}</p>
            <dl className="policy-details">
              <div>
                <dt>
                  <Database aria-hidden="true" size={15} /> Limit
                </dt>
                <dd>{policy.limit}</dd>
              </div>
              {policy.tokenBucket ? (
                <>
                  <div>
                    <dt>
                      <TimerReset aria-hidden="true" size={15} /> Capacity
                    </dt>
                    <dd>{policy.tokenBucket.capacity}</dd>
                  </div>
                  <div>
                    <dt>Refill</dt>
                    <dd>
                      {policy.tokenBucket.refillTokens} /{" "}
                      {policy.tokenBucket.refillPeriodMs / 1000}s
                    </dd>
                  </div>
                </>
              ) : null}
              {policy.slidingWindow ? (
                <div>
                  <dt>
                    <TimerReset aria-hidden="true" size={15} /> Rolling window
                  </dt>
                  <dd>{policy.slidingWindow.windowMs / 1000}s</dd>
                </div>
              ) : null}
              <div>
                <dt>
                  <LockKeyhole aria-hidden="true" size={15} /> Local leasing
                </dt>
                <dd>{policy.localCache.enabled ? "Enabled" : "Disabled"}</dd>
              </div>
              <div>
                <dt>Maximum lease</dt>
                <dd>
                  {policy.localCache.maxLeaseSize} permits /{" "}
                  {policy.localCache.maxLeaseTtlMs}ms
                </dd>
              </div>
              <div>
                <dt>Expected max instances</dt>
                <dd>{policy.localCache.expectedMaxInstances}</dd>
              </div>
              <div>
                <dt>Configured timing budget</dt>
                <dd>{policy.localCache.maxErrorPermits} permits</dd>
              </div>
            </dl>
            {policy.localCache.enabled ? (
              <p className="formula-note">
                {policy.localCache.expectedMaxInstances *
                  (policy.localCache.maxLeaseSize - 1) ===
                policy.localCache.maxErrorPermits ? (
                  <>
                    Configured bound: {policy.localCache.expectedMaxInstances} ×
                    ({policy.localCache.maxLeaseSize} − 1) ={" "}
                    {policy.localCache.maxErrorPermits}.
                  </>
                ) : (
                  <>
                    Configured cache timing-error budget:{" "}
                    {policy.localCache.maxErrorPermits} permits.
                  </>
                )}{" "}
                This is separate from algorithm approximation.
              </p>
            ) : (
              <p className="formula-note">
                Batch-one central mode has zero local-lease timing discrepancy.
              </p>
            )}
            <Link
              className="button secondary"
              to="/playground"
              state={{ policyId: policy.id }}
            >
              Try this policy <ArrowRight aria-hidden="true" size={16} />
            </Link>
          </article>
        ))}
      </div>
    </div>
  );
}
