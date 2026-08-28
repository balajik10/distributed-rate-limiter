import { Navigate, createBrowserRouter } from "react-router-dom";
import { AppShell } from "./AppShell";
import { NotFoundPage } from "./NotFoundPage";

export function ConsoleHydrationFallback() {
  return (
    <main className="route-loading" role="status" aria-live="polite">
      <img src="/console/console-mark.svg" alt="" width="42" height="42" />
      <div>
        <strong>Loading Operations Console</strong>
        <span>Preparing the live system view…</span>
      </div>
    </main>
  );
}

export const router = createBrowserRouter(
  [
    {
      path: "/",
      element: <AppShell />,
      hydrateFallbackElement: <ConsoleHydrationFallback />,
      children: [
        {
          index: true,
          lazy: async () => {
            const { OverviewPage } =
              await import("../features/overview/OverviewPage");
            return { Component: OverviewPage };
          },
        },
        {
          path: "playground",
          lazy: async () => {
            const { PlaygroundPage } =
              await import("../features/playground/PlaygroundPage");
            return { Component: PlaygroundPage };
          },
        },
        {
          path: "policies",
          lazy: async () => {
            const { PoliciesPage } =
              await import("../features/policies/PoliciesPage");
            return { Component: PoliciesPage };
          },
        },
        {
          path: "system",
          lazy: async () => {
            const { SystemPage } =
              await import("../features/health/SystemPage");
            return { Component: SystemPage };
          },
        },
        {
          path: "architecture",
          lazy: async () => {
            const { ArchitecturePage } =
              await import("../features/architecture/ArchitecturePage");
            return { Component: ArchitecturePage };
          },
        },
        { path: "*", element: <NotFoundPage /> },
      ],
    },
  ],
  { basename: "/console" },
);

export function RootRedirect() {
  return <Navigate to="/" replace />;
}
