import { http, HttpResponse } from "msw";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { axe } from "jest-axe";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import { usePolicies } from "../api/queries";
import { tokenBucketPolicy } from "../test/fixtures";
import { server } from "../test/server";
import { createTestQueryClient, TestProviders } from "../test/render";
import { AppShell } from "./AppShell";
import { NotFoundPage } from "./NotFoundPage";
import { useSession } from "./session";

function installHealthyProbes() {
  server.use(
    http.get("*/actuator/health/:kind", () =>
      HttpResponse.json({ status: "UP" }),
    ),
  );
}

function renderShell(
  initialPath = "/",
  indexElement: ReactNode = <h1>Overview test content</h1>,
) {
  const queryClient = createTestQueryClient();
  const router = createMemoryRouter(
    [
      {
        path: "/",
        element: <AppShell />,
        children: [
          { index: true, element: indexElement },
          { path: "policies", element: <h1>Policies test content</h1> },
          { path: "*", element: <NotFoundPage /> },
        ],
      },
    ],
    { initialEntries: [initialPath] },
  );
  const rendered = render(
    <TestProviders queryClient={queryClient}>
      <RouterProvider router={router} />
    </TestProviders>,
  );
  return { ...rendered, queryClient, router };
}

function PolicyProbe() {
  const { apiKey, apiKeyRevision } = useSession();
  const policies = usePolicies(apiKey, apiKeyRevision);
  return (
    <output aria-label="loaded policy">
      {policies.data?.[0]?.id ?? "loading"}
    </output>
  );
}

describe("application shell", () => {
  it("provides landmarks, skip navigation, truthful connection state, and safe external links", async () => {
    installHealthyProbes();
    const rendered = renderShell();

    expect(
      screen.getByRole("link", { name: "Skip to main content" }),
    ).toHaveAttribute("href", "#main-content");
    expect(screen.getByRole("main")).toHaveAttribute("id", "main-content");
    expect(
      screen.getAllByRole("navigation", { name: "Primary navigation" }),
    ).toHaveLength(1);
    await waitFor(() => expect(screen.getByText("Connected")).toBeVisible());

    const swagger = screen.getByRole("link", {
      name: /Swagger.*opens in a new tab/i,
    });
    expect(swagger).toHaveAttribute("target", "_blank");
    expect(swagger.getAttribute("rel")).toContain("noopener");
    expect(swagger).toHaveAttribute(
      "href",
      "http://localhost:8080/swagger-ui/index.html",
    );
    expect((await axe(rendered.container)).violations).toEqual([]);
  });

  it("traps drawer focus, closes with Escape, and restores focus", async () => {
    installHealthyProbes();
    const user = userEvent.setup();
    renderShell();
    const open = screen.getByRole("button", { name: "Open navigation" });

    await user.click(open);
    const dialog = screen.getByRole("dialog", { name: "Navigation" });
    expect(
      within(dialog).getByRole("button", { name: "Close navigation" }),
    ).toHaveFocus();

    await user.tab({ shift: true });
    expect(
      within(dialog).getByRole("link", { name: /Grafana/i }),
    ).toHaveFocus();

    await user.keyboard("{Escape}");
    expect(
      screen.queryByRole("dialog", { name: "Navigation" }),
    ).not.toBeInTheDocument();
    await waitFor(() => expect(open).toHaveFocus());
  });

  it("stops claiming a cached healthy state when the latest refresh fails", async () => {
    installHealthyProbes();
    const { queryClient } = renderShell();
    await waitFor(() => expect(screen.getByText("Connected")).toBeVisible());

    server.use(http.get("*/actuator/health/:kind", () => HttpResponse.error()));
    await queryClient.refetchQueries({ queryKey: ["health"] });

    await waitFor(() =>
      expect(screen.getByText("Needs attention")).toBeVisible(),
    );
    expect(screen.queryByText("Connected")).not.toBeInTheDocument();
    expect(screen.getByText(/Last successful/)).toBeVisible();
  });

  it("closes the drawer after keyboard navigation and updates the page title", async () => {
    installHealthyProbes();
    const user = userEvent.setup();
    const { router } = renderShell();

    await user.click(screen.getByRole("button", { name: "Open navigation" }));
    await user.click(
      within(screen.getByRole("dialog")).getByRole("link", {
        name: "Policies",
      }),
    );

    expect(
      await screen.findByRole("heading", { name: "Policies test content" }),
    ).toBeVisible();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/policies");
    await waitFor(() =>
      expect(document.title).toBe("Policies · Distributed Rate Limiter"),
    );
  });

  it("applies only a complete session credential and clears it explicitly", async () => {
    installHealthyProbes();
    const user = userEvent.setup();
    const observedKeys: Array<string | null> = [];
    server.use(
      http.get("*/api/v1/policies", ({ request }) => {
        observedKeys.push(request.headers.get("X-API-Key"));
        return HttpResponse.json([tokenBucketPolicy]);
      }),
    );
    renderShell(
      "/",
      <>
        <h1>Overview test content</h1>
        <PolicyProbe />
      </>,
    );
    await screen.findByText("api-standard");

    const credentialToggle = screen.getByText("Session key").closest("summary");
    if (!credentialToggle) {
      throw new Error("Session key summary was not rendered");
    }
    expect(credentialToggle).toHaveAttribute(
      "aria-label",
      "Session API key settings",
    );
    await user.click(credentialToggle);
    const credential = screen.getByLabelText("Optional X-API-Key");
    expect(credential).toHaveAttribute("type", "password");
    await user.type(credential, "only-in-memory");
    expect(screen.queryByLabelText("API key is set")).not.toBeInTheDocument();
    expect(observedKeys.filter(Boolean)).toEqual([]);

    await user.click(screen.getByRole("button", { name: "Apply key" }));
    await waitFor(() =>
      expect(observedKeys.filter(Boolean)).toEqual(["only-in-memory"]),
    );
    expect(screen.getByLabelText("API key is set")).toBeInTheDocument();
    expect(window.localStorage.getItem("only-in-memory")).toBeNull();

    await user.click(screen.getByRole("button", { name: "Clear key" }));
    expect(credential).toHaveValue("");
    expect(screen.queryByLabelText("API key is set")).not.toBeInTheDocument();
  });

  it("renders an accessible no-consumption not-found state", async () => {
    installHealthyProbes();
    const rendered = renderShell("/missing");

    expect(
      screen.getByRole("heading", {
        name: "Page not found",
      }),
    ).toBeVisible();
    expect(screen.getByText(/no request was consumed/i)).toBeVisible();
    expect(
      screen.getByRole("link", { name: "Return to overview" }),
    ).toHaveAttribute("href", "/");
    expect((await axe(rendered.container)).violations).toEqual([]);
  });
});
