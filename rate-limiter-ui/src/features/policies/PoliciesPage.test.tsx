import { http, HttpResponse } from "msw";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { axe } from "jest-axe";
import { describe, expect, it } from "vitest";

import { renderWithProviders } from "../../test/render";
import { server } from "../../test/server";
import { slidingLogPolicy, tokenBucketPolicy } from "../../test/fixtures";
import { PoliciesPage } from "./PoliciesPage";

describe("PoliciesPage", () => {
  it("renders loading then trusted read-only policy metadata", async () => {
    server.use(
      http.get("*/api/v1/policies", () =>
        HttpResponse.json([tokenBucketPolicy, slidingLogPolicy]),
      ),
    );
    const rendered = renderWithProviders(<PoliciesPage />);

    expect(screen.getByLabelText("Loading policies")).toBeInTheDocument();
    expect(await screen.findByText("api-standard")).toBeVisible();
    expect(screen.getByText("login-strict")).toBeVisible();
    expect(screen.getByText("100 / 60s")).toBeVisible();
    expect(
      screen.getByText(/Configured bound: 10 × \(10 − 1\) = 90/),
    ).toBeVisible();
    expect(
      screen.getByText(/zero local-lease timing discrepancy/i),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", {
        name: /create|edit|delete|reset|enable|disable/i,
      }),
    ).not.toBeInTheDocument();
    expect((await axe(rendered.container)).violations).toEqual([]);
  });

  it("filters by algorithm, failure mode, and local-leasing truth", async () => {
    server.use(
      http.get("*/api/v1/policies", () =>
        HttpResponse.json([tokenBucketPolicy, slidingLogPolicy]),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<PoliciesPage />);
    await screen.findByText("api-standard");

    await user.selectOptions(
      screen.getByLabelText("Algorithm"),
      "SLIDING_WINDOW_LOG",
    );
    expect(screen.queryByText("api-standard")).not.toBeInTheDocument();
    expect(screen.getByText("login-strict")).toBeVisible();

    await user.selectOptions(
      screen.getByLabelText("Failure mode"),
      "FAIL_OPEN",
    );
    expect(screen.getByText("No matching policies")).toBeVisible();

    await user.selectOptions(screen.getByLabelText("Algorithm"), "ALL");
    await user.selectOptions(screen.getByLabelText("Local leasing"), "ENABLED");
    expect(screen.getByText("api-standard")).toBeVisible();
    expect(screen.queryByText("login-strict")).not.toBeInTheDocument();
  });

  it("shows an empty state rather than invented policies", async () => {
    server.use(http.get("*/api/v1/policies", () => HttpResponse.json([])));
    renderWithProviders(<PoliciesPage />);

    expect(await screen.findByText("No matching policies")).toBeVisible();
    expect(screen.queryByText("api-standard")).not.toBeInTheDocument();
  });

  it("shows an error with an explicit safe GET retry", async () => {
    let calls = 0;
    server.use(
      http.get("*/api/v1/policies", () => {
        calls += 1;
        return calls === 1
          ? HttpResponse.json({ title: "Unavailable" }, { status: 503 })
          : HttpResponse.json([tokenBucketPolicy]);
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<PoliciesPage />);

    expect(
      await screen.findByText("Policy metadata unavailable."),
    ).toBeVisible();
    expect(screen.getByText("No fallback fixtures are shown.")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Retry safe GET" }));

    expect(await screen.findByText("api-standard")).toBeVisible();
    expect(calls).toBe(2);
  });
});
