import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, type RenderOptions } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import type { ReactElement, ReactNode } from "react";

import { SessionProvider } from "../app/session";
import { TrafficLabProvider } from "../features/traffic-lab/TrafficLabContext";

export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
}

export function TestProviders({
  children,
  queryClient = createTestQueryClient(),
}: {
  children: ReactNode;
  queryClient?: QueryClient;
}) {
  return (
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <TrafficLabProvider>{children}</TrafficLabProvider>
      </SessionProvider>
    </QueryClientProvider>
  );
}

export function renderWithProviders(
  ui: ReactElement,
  {
    route = "/",
    ...options
  }: Omit<RenderOptions, "wrapper"> & { route?: string } = {},
) {
  return render(
    <TestProviders>
      <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
    </TestProviders>,
    options,
  );
}
