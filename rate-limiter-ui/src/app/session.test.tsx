import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import {
  appendRecord,
  MAX_SESSION_RECORDS,
  percentile,
  SessionProvider,
  summarizeSession,
  useSession,
} from "./session";
import { decision, record } from "../test/fixtures";

describe("browser-session ledger", () => {
  it("keeps newest-first order and enforces the 200-record bound", () => {
    let records = Array.from({ length: MAX_SESSION_RECORDS }, (_, index) =>
      record({ id: `record-${String(index)}` }),
    );

    records = appendRecord(records, record({ id: "newest" }));

    expect(records).toHaveLength(MAX_SESSION_RECORDS);
    expect(records[0]?.id).toBe("newest");
    expect(records.at(-1)?.id).toBe(
      `record-${String(MAX_SESSION_RECORDS - 2)}`,
    );
    for (const item of records) {
      expect(item).not.toHaveProperty("key");
      expect(item).not.toHaveProperty("apiKey");
    }
  });

  it("uses nearest-rank percentiles without mutating the input", () => {
    const values = [100, 10, 40, 20, 30];

    expect(percentile([], 0.5)).toBe(0);
    expect(percentile(values, 0.5)).toBe(30);
    expect(percentile(values, 0.95)).toBe(100);
    expect(percentile(values, 0.99)).toBe(100);
    expect(percentile(values, 0)).toBe(10);
    expect(values).toEqual([100, 10, 40, 20, 30]);
  });

  it("summarizes outcomes and excludes no-response latency from received-response percentiles", () => {
    const summary = summarizeSession([
      record({ id: "normal", latencyMs: 10 }),
      record({
        id: "degraded",
        classification: "DEGRADED_ALLOWED",
        latencyMs: 20,
        decision: decision({ degraded: true, source: "FAIL_OPEN" }),
      }),
      record({
        id: "quota",
        classification: "QUOTA_DENIED",
        httpStatus: 429,
        latencyMs: 30,
      }),
      record({
        id: "backend",
        classification: "BACKEND_DENIED",
        httpStatus: 503,
        latencyMs: 40,
      }),
      record({
        id: "unknown",
        classification: "UNKNOWN_OUTCOME",
        httpStatus: null,
        latencyMs: 9_999,
      }),
      record({
        id: "client",
        classification: "CLIENT_ERROR",
        httpStatus: 400,
        latencyMs: 50,
      }),
    ]);

    expect(summary).toEqual({
      decisions: 6,
      allowed: 2,
      denied: 2,
      degraded: 1,
      unknown: 1,
      latency: { p50: 30, p95: 50, p99: 50 },
    });
  });

  it("returns zeroed aggregates for a fresh browser session", () => {
    expect(summarizeSession([])).toEqual({
      decisions: 0,
      allowed: 0,
      denied: 0,
      degraded: 0,
      unknown: 0,
      latency: { p50: 0, p95: 0, p99: 0 },
    });
  });
});

function SessionProbe() {
  const session = useSession();
  return (
    <div>
      <output aria-label="record count">{session.summary.decisions}</output>
      <output aria-label="credential">{session.apiKey}</output>
      <output aria-label="credential revision">{session.apiKeyRevision}</output>
      <output aria-label="theme">{session.theme}</output>
      <button type="button" onClick={() => session.setApiKey("runtime-secret")}>
        Set credential
      </button>
      <button
        type="button"
        onClick={() => session.addRecord(record({ id: "from-ui" }))}
      >
        Add decision
      </button>
      <button type="button" onClick={session.toggleTheme}>
        Toggle theme
      </button>
    </div>
  );
}

describe("SessionProvider", () => {
  it("keeps credentials and decision history in memory while persisting only theme", async () => {
    const user = userEvent.setup();
    render(
      <SessionProvider>
        <SessionProbe />
      </SessionProvider>,
    );

    await user.click(screen.getByRole("button", { name: "Set credential" }));
    await user.click(screen.getByRole("button", { name: "Add decision" }));
    await user.click(screen.getByRole("button", { name: "Toggle theme" }));

    expect(screen.getByLabelText("credential")).toHaveTextContent(
      "runtime-secret",
    );
    expect(screen.getByLabelText("credential revision")).toHaveTextContent("1");
    expect(screen.getByLabelText("record count")).toHaveTextContent("1");
    expect(screen.getByLabelText("theme")).toHaveTextContent("light");
    expect(window.localStorage.getItem("rate-limiter-theme")).toBe("light");
    expect(JSON.stringify(window.localStorage)).not.toContain("runtime-secret");
    expect(JSON.stringify(window.sessionStorage)).not.toContain(
      "runtime-secret",
    );
  });

  it("restores only a valid light theme and clears memory on remount", async () => {
    const user = userEvent.setup();
    window.localStorage.setItem("rate-limiter-theme", "light");
    const first = render(
      <SessionProvider>
        <SessionProbe />
      </SessionProvider>,
    );
    expect(screen.getByLabelText("theme")).toHaveTextContent("light");

    await user.click(screen.getByRole("button", { name: "Set credential" }));
    first.unmount();
    const second = render(
      <SessionProvider>
        <SessionProbe />
      </SessionProvider>,
    );

    expect(screen.getByLabelText("credential")).toBeEmptyDOMElement();
    expect(screen.getByLabelText("credential revision")).toHaveTextContent("0");
    expect(screen.getByLabelText("record count")).toHaveTextContent("0");

    second.unmount();
    window.localStorage.setItem("rate-limiter-theme", "unexpected");
    render(
      <SessionProvider>
        <SessionProbe />
      </SessionProvider>,
    );
    expect(screen.getByLabelText("theme")).toHaveTextContent("dark");
  });

  it("throws a clear error when the hook is used outside its provider", () => {
    function InvalidConsumer() {
      useSession();
      return null;
    }

    expect(() => render(<InvalidConsumer />)).toThrow(
      "useSession must be used inside SessionProvider",
    );
  });
});
