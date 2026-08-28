import { http, HttpResponse } from "msw";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { axe } from "jest-axe";
import { describe, expect, it, vi } from "vitest";

import { renderWithProviders } from "../../test/render";
import { server } from "../../test/server";
import { SystemPage } from "./SystemPage";

describe("SystemPage", () => {
  it("renders real probe truth, external URLs, and an accessible read-only outage guide", async () => {
    server.use(
      http.get("*/actuator/health/:kind", () =>
        HttpResponse.json({ status: "UP" }),
      ),
    );
    const writeText = vi
      .fn<(value: string) => Promise<void>>()
      .mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText },
    });
    const rendered = renderWithProviders(<SystemPage />);

    expect(await screen.findAllByText("UP")).not.toHaveLength(0);
    expect(screen.getByText("Java liveness")).toBeVisible();
    expect(screen.getByText("Backend readiness")).toBeVisible();
    expect(screen.getByRole("link", { name: /Prometheus/i })).toHaveAttribute(
      "href",
      "http://localhost:9090/",
    );
    expect(screen.getByText(/browser cannot stop containers/i)).toBeVisible();
    expect(
      screen.getByText(/COMPOSE_PROJECT_NAME/).closest("pre"),
    ).toHaveAttribute("tabindex", "0");

    await userEvent.click(
      screen.getByRole("button", { name: "Copy commands" }),
    );
    expect(writeText).toHaveBeenCalledOnce();
    expect(writeText.mock.calls[0]?.[0]).toContain(
      "./scripts/demo-failure-modes.sh",
    );
    expect(screen.getByText("Outage commands copied")).toBeInTheDocument();
    expect((await axe(rendered.container)).violations).toEqual([]);
  });

  it("uses Unknown / offline rather than inventing component health", async () => {
    server.use(http.get("*/actuator/health/:kind", () => HttpResponse.error()));
    renderWithProviders(<SystemPage />);

    expect(await screen.findAllByText("Unknown / offline")).toHaveLength(2);
  });
});
