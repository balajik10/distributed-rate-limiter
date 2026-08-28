import { http, HttpResponse } from "msw";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { SessionProvider, useSession } from "../../app/session";
import { decision, trafficConfig } from "../../test/fixtures";
import { server } from "../../test/server";
import { TrafficLabProvider, useTrafficLab } from "./TrafficLabContext";

function LabProbe() {
  const lab = useTrafficLab();
  const session = useSession();
  return (
    <div>
      <output aria-label="running">{String(lab.state.running)}</output>
      <output aria-label="scheduled">{lab.state.scheduled}</output>
      <output aria-label="completed">{lab.state.completed}</output>
      <output aria-label="session decisions">
        {session.summary.decisions}
      </output>
      <button
        type="button"
        onClick={() => lab.start(trafficConfig({ totalRequests: 1 }))}
      >
        Start
      </button>
      <button type="button" onClick={lab.stop}>
        Stop
      </button>
    </div>
  );
}

function renderProvider() {
  return render(
    <SessionProvider>
      <TrafficLabProvider>
        <LabProbe />
      </TrafficLabProvider>
    </SessionProvider>,
  );
}

describe("TrafficLabProvider", () => {
  it("integrates a completed scheduler result into both run state and session history", async () => {
    let calls = 0;
    server.use(
      http.post("*/api/v1/rate-limits/check", () => {
        calls += 1;
        return HttpResponse.json(decision(), {
          headers: { "X-Request-Id": "request-1" },
        });
      }),
    );
    const user = userEvent.setup();
    renderProvider();

    await user.click(screen.getByRole("button", { name: "Start" }));

    await waitFor(() =>
      expect(screen.getByLabelText("completed")).toHaveTextContent("1"),
    );
    expect(screen.getByLabelText("running")).toHaveTextContent("false");
    expect(screen.getByLabelText("scheduled")).toHaveTextContent("1");
    expect(screen.getByLabelText("session decisions")).toHaveTextContent("1");
    expect(calls).toBe(1);
  });

  it("ignores a second start while a run is active", async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    let calls = 0;
    server.use(
      http.post("*/api/v1/rate-limits/check", async () => {
        calls += 1;
        await gate;
        return HttpResponse.json(decision());
      }),
    );
    const user = userEvent.setup();
    renderProvider();

    await user.click(screen.getByRole("button", { name: "Start" }));
    await user.click(screen.getByRole("button", { name: "Start" }));
    expect(calls).toBe(1);
    expect(screen.getByLabelText("running")).toHaveTextContent("true");

    release();
    await waitFor(() =>
      expect(screen.getByLabelText("completed")).toHaveTextContent("1"),
    );
  });

  it("disposes scheduling timers on provider unmount without aborting the sent request", async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      http.post("*/api/v1/rate-limits/check", async () => {
        await gate;
        return HttpResponse.json(decision());
      }),
    );
    const clearInterval = vi.spyOn(window, "clearInterval");
    const user = userEvent.setup();
    const rendered = renderProvider();

    await user.click(screen.getByRole("button", { name: "Start" }));
    rendered.unmount();

    expect(clearInterval).toHaveBeenCalled();
    release();
  });

  it("rejects hook use outside its provider", () => {
    function InvalidConsumer() {
      useTrafficLab();
      return null;
    }

    expect(() =>
      render(
        <SessionProvider>
          <InvalidConsumer />
        </SessionProvider>,
      ),
    ).toThrow("useTrafficLab must be used inside TrafficLabProvider");
  });
});
