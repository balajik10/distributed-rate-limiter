import {
  Box,
  Braces,
  Clock3,
  Database,
  Gauge,
  Layers3,
  Network,
  ShieldAlert,
  Waypoints,
} from "lucide-react";
import { PageHeader } from "../../components/PageHeader";

const explanations = [
  [
    "Atomic, not GET then SET",
    "A client-side read then write races with other requests. One Lua script makes the state transition atomic on the executing Redis primary.",
    Braces,
  ],
  [
    "Redis owns enforcement time",
    "Redis server time keeps application-node clock skew out of quota enforcement.",
    Clock3,
  ],
  [
    "Charge before cache",
    "Permit batches are deducted centrally before a short-lived tail is held in Caffeine. LOCAL_LEASE is never an uncharged allow.",
    Layers3,
  ],
  [
    "Ambiguous means do not retry",
    "A timeout can hide a successful Lua execution. Blind retry may consume permits twice.",
    ShieldAlert,
  ],
  [
    "Failure is policy-specific",
    "Fail open preserves availability with unknown quota state. Fail closed protects strict operations by blocking.",
    Box,
  ],
  [
    "One hot key is one bottleneck",
    "Atomic enforcement is global for a key, but Redis execution and network scheduling do not guarantee fairness among callers.",
    Gauge,
  ],
] as const;

export function ArchitecturePage() {
  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="Architecture"
        title="A centralized decision, without a centralized explanation gap."
        description="Use this page as an interview-ready map from HTTP request to atomic Redis decision, local lease, and observable outcome."
      />
      <section className="flow-panel" aria-labelledby="flow-heading">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">Request path</p>
            <h2 id="flow-heading">One acquisition</h2>
          </div>
        </div>
        <div
          className="request-flow"
          role="img"
          aria-label="Client calls Spring API, Spring resolves a trusted policy, checks a charged local lease, otherwise runs one atomic Redis Lua script, then returns a decision"
        >
          <div>
            <Waypoints aria-hidden="true" />
            <strong>1 · Client</strong>
            <span>POST acquisition</span>
          </div>
          <i aria-hidden="true">→</i>
          <div>
            <Network aria-hidden="true" />
            <strong>2 · Spring API</strong>
            <span>Resolve trusted policy</span>
          </div>
          <i aria-hidden="true">→</i>
          <div>
            <Layers3 aria-hidden="true" />
            <strong>3 · Local lease</strong>
            <span>Use charged tail if eligible</span>
          </div>
          <i aria-hidden="true">→</i>
          <div>
            <Database aria-hidden="true" />
            <strong>4 · Redis Lua</strong>
            <span>Atomic check + reserve</span>
          </div>
          <i aria-hidden="true">→</i>
          <div>
            <Box aria-hidden="true" />
            <strong>5 · Decision</strong>
            <span>Allow, deny, or degrade</span>
          </div>
        </div>
        <ol className="equivalent-flow">
          <li>A client calls the Spring HTTP API.</li>
          <li>The server resolves a trusted policy.</li>
          <li>
            A valid local charged permit tail may satisfy an eligible unit
            request.
          </li>
          <li>
            Otherwise one Redis Lua script atomically checks and reserves.
          </li>
          <li>
            The service returns allow, quota deny, fail-open, or fail-closed.
          </li>
        </ol>
      </section>
      <div className="explanation-grid">
        {explanations.map(([title, text, Icon]) => (
          <article className="explanation-card" key={title}>
            <Icon aria-hidden="true" size={21} />
            <h2>{title}</h2>
            <p>{text}</p>
          </article>
        ))}
      </div>
      <section className="bound-panel" aria-labelledby="bound-heading">
        <div>
          <p className="eyebrow">Leasing approximation</p>
          <h2 id="bound-heading">Cache timing discrepancy ≤ M × (B − 1)</h2>
          <p>
            <strong>M</strong> is the actual number of live lease-holding
            instances; <strong>B</strong> is the maximum charged batch size.
          </p>
        </div>
        <ul>
          <li>The bound assumes one live tail per instance and logical key.</li>
          <li>Fail-open outage traffic is outside this bound.</li>
          <li>Sliding-window-counter boundary approximation is separate.</li>
          <li>
            Expiry or process churn can waste charged quota without creating
            uncharged admissions.
          </li>
        </ul>
      </section>
      <section aria-labelledby="algorithm-heading">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Trade-offs</p>
            <h2 id="algorithm-heading">Three algorithms, three promises</h2>
          </div>
        </div>
        <div className="algorithm-grid">
          <article>
            <h3>Token bucket</h3>
            <p>
              O(1) state and intentional bursts. It is not an exact “L in every
              rolling window” rule.
            </p>
          </article>
          <article>
            <h3>Sliding-window log</h3>
            <p>
              Exact for a rolling interval in central batch-one mode, with
              O(live events) memory and work.
            </p>
          </article>
          <article>
            <h3>Sliding-window counter</h3>
            <p>
              O(1) state with a deliberate approximation near window boundaries.
            </p>
          </article>
        </div>
      </section>
    </div>
  );
}
