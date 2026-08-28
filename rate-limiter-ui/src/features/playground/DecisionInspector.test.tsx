import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { axe } from "jest-axe";
import { describe, expect, it, vi } from "vitest";

import { decision, record } from "../../test/fixtures";
import { DecisionInspector } from "./DecisionInspector";

function recordWithoutDecision(overrides: Parameters<typeof record>[0] = {}) {
  const value = record(overrides);
  delete value.decision;
  return value;
}

describe("DecisionInspector", () => {
  it.each([
    ["NORMAL_ALLOWED", "Request allowed", "HTTP 200"],
    ["QUOTA_DENIED", "Rate limit reached", "HTTP 429"],
    ["DEGRADED_ALLOWED", "Allowed — degraded fail-open", "HTTP 200"],
    ["BACKEND_DENIED", "Backend unavailable — request blocked", "HTTP 503"],
    ["CLIENT_ERROR", "Request rejected", "HTTP 400"],
    ["SERVER_ERROR", "Service error", "HTTP 500"],
    ["PROTOCOL_ERROR", "Unexpected response", "HTTP 200"],
  ] as const)(
    "renders %s with text as well as color",
    (classification, heading, status) => {
      render(
        <DecisionInspector
          record={record({
            classification,
            httpStatus: Number(status.slice(5)),
          })}
        />,
      );

      expect(screen.getByRole("heading", { name: heading })).toBeVisible();
      expect(screen.getByText(status)).toBeVisible();
    },
  );

  it("explains an ambiguous no-response outcome without exposing raw HTML", () => {
    render(
      <DecisionInspector
        record={recordWithoutDecision({
          classification: "UNKNOWN_OUTCOME",
          httpStatus: null,
        })}
      />,
    );

    expect(
      screen.getByRole("heading", {
        name: "Outcome unknown — do not retry blindly",
      }),
    ).toBeVisible();
    expect(screen.getByText("No HTTP response")).toBeVisible();
    expect(
      screen.getByText(/may still have executed and consumed permits/i),
    ).toBeVisible();
    expect(screen.getByText(/not retried automatically/i)).toBeVisible();
  });

  it("formats sentinels, approximation, source explanation, and retry values accurately", () => {
    render(
      <DecisionInspector
        record={record({
          classification: "DEGRADED_ALLOWED",
          decision: decision({
            remaining: -1,
            resetAtEpochMs: null,
            retryAfterMs: -1,
            source: "FAIL_OPEN",
            degraded: true,
            approximate: true,
          }),
        })}
      />,
    );

    expect(screen.getByText("Unknown")).toBeVisible();
    expect(screen.getByText("Unavailable")).toBeVisible();
    expect(screen.getByText("Not provided")).toBeVisible();
    expect(screen.getByText("Approximate")).toBeVisible();
    expect(
      screen.getByText(/allowed the request without known quota state/i),
    ).toBeVisible();
    expect(
      screen.getByText(
        /configured algorithm or a centrally charged local permit lease/i,
      ),
    ).toBeVisible();
  });

  it("shows zero retry as now and rounds positive retry durations up", () => {
    const first = render(
      <DecisionInspector
        record={record({ decision: decision({ retryAfterMs: 0 }) })}
      />,
    );
    expect(screen.getByText("Now")).toBeVisible();
    first.unmount();

    render(
      <DecisionInspector
        record={record({ decision: decision({ retryAfterMs: 1_001 }) })}
      />,
    );
    expect(screen.getByText("2 seconds (informational only)")).toBeVisible();
  });

  it("renders protocol warnings, selected headers, and escaped JSON", async () => {
    const { container } = render(
      <DecisionInspector
        record={record({
          protocolWarnings: ["Response body and header disagree about limit."],
          headers: {
            "X-RateLimit-Limit": "99",
            "X-RateLimit-Reset": "1800000000",
          },
          rawJson: { unsafe: "<script>alert('x')</script>" },
        })}
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("disagree about limit");
    expect(screen.getByText("X-RateLimit-Limit")).toBeVisible();
    expect(screen.getByText(/epoch seconds/i)).toBeVisible();
    await userEvent.click(screen.getByText("Escaped raw JSON"));
    expect(screen.getByText(/<script>alert\('x'\)<\/script>/)).toBeVisible();
    expect(container.innerHTML).not.toContain("<script>alert");
  });

  it("copies only the non-secret response request ID", async () => {
    const writeText = vi
      .fn<(value: string) => Promise<void>>()
      .mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText },
    });
    render(<DecisionInspector record={record()} />);

    await userEvent.click(screen.getByRole("button", { name: "Copy" }));

    expect(writeText).toHaveBeenCalledOnce();
    expect(writeText).toHaveBeenCalledWith("request-1");
    expect(screen.getByText("Request ID copied")).toBeInTheDocument();
  });

  it("has no detectable accessibility violations in success, denial, degraded, and error states", async () => {
    const variants = [
      record(),
      record({
        classification: "QUOTA_DENIED",
        httpStatus: 429,
        decision: decision({
          allowed: false,
          grantedPermits: 0,
          reason: "LIMIT_EXCEEDED",
        }),
      }),
      record({
        classification: "DEGRADED_ALLOWED",
        decision: decision({
          degraded: true,
          source: "FAIL_OPEN",
          approximate: true,
        }),
      }),
      recordWithoutDecision({
        classification: "SERVER_ERROR",
        httpStatus: 500,
        problem: {
          title: "Internal server error",
          detail: "The request could not be completed",
        },
      }),
    ];

    for (const variant of variants) {
      const rendered = render(<DecisionInspector record={variant} />);
      const results = await axe(rendered.container);
      expect(results.violations).toEqual([]);
      rendered.unmount();
    }
  });
});
