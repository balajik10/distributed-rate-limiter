import { http, HttpResponse } from "msw";
import { screen } from "@testing-library/react";
import { axe } from "jest-axe";
import { describe, expect, it } from "vitest";

import { tokenBucketPolicy } from "../../test/fixtures";
import { renderWithProviders } from "../../test/render";
import { server } from "../../test/server";
import { OverviewPage } from "./OverviewPage";

function installOverviewApis() {
  server.use(
    http.get("*/api/v1/policies", () => HttpResponse.json([tokenBucketPolicy])),
    http.get("*/actuator/health/:kind", () =>
      HttpResponse.json({ status: "UP" }),
    ),
  );
}

describe("OverviewPage", () => {
  it("shows only real zero session totals, then real readiness and policy data", async () => {
    installOverviewApis();
    const rendered = renderWithProviders(<OverviewPage />);

    expect(
      screen.getByRole("heading", { name: "Checking service" }),
    ).toBeVisible();
    expect(
      screen.getByRole("heading", { name: "This browser session" }),
    ).toBeVisible();
    expect(screen.getByText("No requests yet")).toBeVisible();
    expect(
      screen.getByText("Run a check to see the decision path."),
    ).toBeVisible();
    expect(
      screen.getByRole("img", {
        name: "0 allowed, 0 denied, 0 degraded, 0 unknown",
      }),
    ).toBeInTheDocument();

    expect(
      await screen.findByRole("heading", { name: "Ready to enforce limits" }),
    ).toBeVisible();
    expect(await screen.findByText("api-standard")).toBeVisible();
    expect((await axe(rendered.container)).violations).toEqual([]);
  });

  it("truthfully reports an unreachable service and unavailable policies", async () => {
    server.use(
      http.get("*/api/v1/policies", () => HttpResponse.error()),
      http.get("*/actuator/health/:kind", () => HttpResponse.error()),
    );
    renderWithProviders(<OverviewPage />);

    expect(
      await screen.findByRole("heading", { name: "Cannot reach the service" }),
    ).toBeVisible();
    expect(await screen.findByText("Policies unavailable.")).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Retry safe GET" }),
    ).toBeVisible();
  });
});
