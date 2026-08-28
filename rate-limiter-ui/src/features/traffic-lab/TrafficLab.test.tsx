import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { axe } from "jest-axe";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { AcquisitionRecord } from "../../api/client";
import type { TrafficConfig } from "../../api/contracts";
import {
  decision,
  record,
  slidingLogPolicy,
  tokenBucketPolicy,
} from "../../test/fixtures";
import { TrafficLab } from "./TrafficLab";

const lab = vi.hoisted(() => ({
  start: vi.fn<(config: TrafficConfig) => void>(),
  stop: vi.fn(),
  state: {
    running: false,
    scheduled: 0,
    completed: 0,
    inFlight: 0,
    records: [] as AcquisitionRecord[],
  },
}));

vi.mock("./TrafficLabContext", () => ({ useTrafficLab: () => lab }));

beforeEach(() => {
  lab.start.mockReset();
  lab.stop.mockReset();
  lab.state.running = false;
  lab.state.scheduled = 0;
  lab.state.completed = 0;
  lab.state.inFlight = 0;
  lab.state.records = [];
});

describe("TrafficLab", () => {
  it("renders the safe bounded defaults and honest benchmark disclaimer", async () => {
    const rendered = render(
      <TrafficLab policies={[tokenBucketPolicy, slidingLogPolicy]} />,
    );

    expect(screen.getByLabelText("Total requests")).toHaveValue(20);
    expect(screen.getByLabelText("Target rate")).toHaveValue(5);
    expect(screen.getByLabelText("Concurrency")).toHaveValue(2);
    expect(screen.getByLabelText(/Fixed key/)).toBeChecked();
    expect(screen.getByText("4 seconds")).toBeVisible();
    expect(
      screen.getByText(
        /functional browser demonstration, not a performance benchmark/i,
      ),
    ).toBeVisible();
    expect(screen.getByText(/No traffic run yet/i)).toBeVisible();
    expect((await axe(rendered.container)).violations).toEqual([]);
  });

  it("blocks a run whose estimated duration exceeds sixty seconds", async () => {
    const user = userEvent.setup();
    render(<TrafficLab policies={[tokenBucketPolicy]} />);
    const total = screen.getByLabelText("Total requests");
    const rate = screen.getByLabelText("Target rate");

    await user.clear(total);
    await user.type(total, "61");
    await user.clear(rate);
    await user.type(rate, "1");

    expect(screen.getByText("61 seconds")).toHaveClass("error-text");
    expect(screen.getByText(/Maximum is 60 seconds/i)).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Start bounded run" }),
    ).toBeDisabled();
    expect(lab.start).not.toHaveBeenCalled();
  });

  it("applies the strict-login preset and starts exactly the requested finite run", async () => {
    const user = userEvent.setup();
    render(<TrafficLab policies={[tokenBucketPolicy, slidingLogPolicy]} />);

    await user.click(
      screen.getByRole("button", { name: "Strict login: 6 requests" }),
    );
    expect(screen.getByLabelText("Policy")).toHaveValue("login-strict");
    expect(screen.getByLabelText("Total requests")).toHaveValue(6);
    expect(screen.getByLabelText("Target rate")).toHaveValue(5);
    expect(screen.getByLabelText("Concurrency")).toHaveValue(1);

    await user.click(screen.getByRole("button", { name: "Start bounded run" }));
    expect(lab.start).toHaveBeenCalledOnce();
    const started = lab.start.mock.calls[0]?.[0];
    expect(started).toMatchObject({
      policyId: "login-strict",
      permits: 1,
      totalRequests: 6,
      targetRps: 5,
      concurrency: 1,
      keyMode: "FIXED",
    });
    expect(started?.baseKey).toMatch(/^ui-lab:login-strict:/);
  });

  it("enforces the selected policy permit maximum before starting", async () => {
    const user = userEvent.setup();
    render(<TrafficLab policies={[slidingLogPolicy]} />);
    const permits = screen.getByLabelText("Permits / request");
    await user.clear(permits);
    await user.type(permits, "6");
    await user.click(screen.getByRole("button", { name: "Start bounded run" }));

    expect(
      await screen.findByText("Selected policy allows at most 5"),
    ).toBeVisible();
    expect(permits).toHaveFocus();
    expect(lab.start).not.toHaveBeenCalled();
  });

  it("prevents concurrent runs and exposes an explicit stop action", async () => {
    lab.state.running = true;
    lab.state.scheduled = 2;
    lab.state.inFlight = 2;
    const user = userEvent.setup();
    render(<TrafficLab policies={[tokenBucketPolicy]} />);

    expect(
      screen.queryByRole("button", { name: "Start bounded run" }),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("Policy")).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Stop run" }));
    expect(lab.stop).toHaveBeenCalledOnce();
  });

  it("reports only actual completed records and their source/latency distribution", async () => {
    lab.state.scheduled = 3;
    lab.state.completed = 3;
    lab.state.records = [
      record({ sequence: 1, completionOrder: 2, latencyMs: 30 }),
      record({
        id: "quota",
        sequence: 2,
        completionOrder: 1,
        classification: "QUOTA_DENIED",
        httpStatus: 429,
        latencyMs: 10,
        decision: decision({
          allowed: false,
          grantedPermits: 0,
          reason: "LIMIT_EXCEEDED",
        }),
      }),
      record({
        id: "lease",
        sequence: 3,
        completionOrder: 3,
        latencyMs: 20,
        decision: decision({ source: "LOCAL_LEASE", approximate: true }),
      }),
    ];
    const user = userEvent.setup();
    const rendered = render(<TrafficLab policies={[tokenBucketPolicy]} />);

    await user.clear(screen.getByLabelText("Total requests"));
    await user.type(screen.getByLabelText("Total requests"), "3");
    expect(screen.getByText("Complete")).toBeVisible();
    expect(
      screen.getByText(
        "Completed demo requests; submission and completion order are tracked separately",
      ),
    ).toBeVisible();
    expect(screen.getByText("10.0 ms")).toBeVisible();
    expect(screen.getAllByText("30.0 ms")).not.toHaveLength(0);
    expect(screen.getAllByText("LOCAL LEASE")).not.toHaveLength(0);
    expect((await axe(rendered.container)).violations).toEqual([]);
  });
});
