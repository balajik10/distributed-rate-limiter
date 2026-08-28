import { http, HttpResponse } from "msw";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { axe } from "jest-axe";
import { describe, expect, it } from "vitest";

import { slidingLogPolicy, tokenBucketPolicy } from "../../test/fixtures";
import { renderWithProviders } from "../../test/render";
import { server } from "../../test/server";
import { PlaygroundPage } from "./PlaygroundPage";

describe("PlaygroundPage", () => {
  it("renders loading, then the single-check form from real policies", async () => {
    server.use(
      http.get("*/api/v1/policies", () =>
        HttpResponse.json([tokenBucketPolicy, slidingLogPolicy]),
      ),
    );
    const rendered = renderWithProviders(<PlaygroundPage />, {
      route: "/playground",
    });

    expect(screen.getByLabelText("Loading policies")).toBeVisible();
    expect(
      await screen.findByRole("heading", { name: "Acquire permits" }),
    ).toBeVisible();
    expect(screen.getByRole("tab", { name: "Single check" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByRole("tabpanel")).toHaveAttribute(
      "aria-labelledby",
      "single-tab",
    );
    expect((await axe(rendered.container)).violations).toEqual([]);
  });

  it("switches keyboard-accessibly to the bounded Demo Traffic Lab", async () => {
    server.use(
      http.get("*/api/v1/policies", () =>
        HttpResponse.json([tokenBucketPolicy, slidingLogPolicy]),
      ),
    );
    const user = userEvent.setup();
    const rendered = renderWithProviders(<PlaygroundPage />, {
      route: "/playground",
    });
    const trafficTab = await screen.findByRole("tab", {
      name: "Demo Traffic Lab",
    });

    trafficTab.focus();
    await user.keyboard("{Enter}");

    expect(trafficTab).toHaveAttribute("aria-selected", "true");
    expect(
      screen.getByRole("heading", { name: "Demo Traffic Lab" }),
    ).toBeVisible();
    expect(screen.getByText(/not a performance benchmark/i)).toBeVisible();
    expect(
      screen.getByRole("progressbar", { name: "Traffic run progress" }),
    ).toBeInTheDocument();
    expect((await axe(rendered.container)).violations).toEqual([]);
  });

  it("shows empty policy truth without rendering a mutation form", async () => {
    server.use(http.get("*/api/v1/policies", () => HttpResponse.json([])));
    renderWithProviders(<PlaygroundPage />, { route: "/playground" });

    expect(await screen.findByText("No policies available")).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Check limit" }),
    ).not.toBeInTheDocument();
  });

  it("requires an explicit manual retry after a policy-read failure", async () => {
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
    renderWithProviders(<PlaygroundPage />, { route: "/playground" });

    expect(
      await screen.findByText("Policies could not be loaded."),
    ).toBeVisible();
    expect(calls).toBe(1);
    await user.click(screen.getByRole("button", { name: "Retry safe GET" }));

    expect(
      await screen.findByRole("heading", { name: "Acquire permits" }),
    ).toBeVisible();
    expect(calls).toBe(2);
  });
});
