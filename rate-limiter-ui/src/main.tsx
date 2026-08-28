import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "react-router-dom";
import { SessionProvider } from "./app/session";
import { router } from "./app/router";
import { TrafficLabProvider } from "./features/traffic-lab/TrafficLabContext";
import "./styles/index.css";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false, refetchOnReconnect: false },
    mutations: { retry: false },
  },
});

const root = document.getElementById("root");
if (root === null) throw new Error("Application root element is missing");

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <TrafficLabProvider>
          <RouterProvider router={router} />
        </TrafficLabProvider>
      </SessionProvider>
    </QueryClientProvider>
  </StrictMode>,
);
