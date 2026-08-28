import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { fetchHealth, fetchPolicies } from "./client";

export function useDocumentVisible(): boolean {
  const [visible, setVisible] = useState(
    () => document.visibilityState === "visible",
  );
  useEffect(() => {
    const update = () => setVisible(document.visibilityState === "visible");
    document.addEventListener("visibilitychange", update);
    return () => document.removeEventListener("visibilitychange", update);
  }, []);
  return visible;
}

export function usePolicies(apiKey: string, apiKeyRevision: number) {
  return useQuery({
    queryKey: ["policies", apiKey ? "authenticated" : "public", apiKeyRevision],
    queryFn: () => fetchPolicies(apiKey || undefined),
    retry: false,
    staleTime: 30_000,
  });
}

export function useHealthQueries() {
  const visible = useDocumentVisible();
  const options = {
    retry: false,
    refetchInterval: visible ? 10_000 : false,
    refetchOnWindowFocus: true,
  } as const;
  const liveness = useQuery({
    queryKey: ["health", "liveness"],
    queryFn: () => fetchHealth("liveness"),
    ...options,
  });
  const readiness = useQuery({
    queryKey: ["health", "readiness"],
    queryFn: () => fetchHealth("readiness"),
    ...options,
  });
  return { liveness, readiness };
}
