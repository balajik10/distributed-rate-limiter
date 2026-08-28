function publicPort(
  name:
    | "VITE_APP_PUBLIC_PORT"
    | "VITE_GRAFANA_PUBLIC_PORT"
    | "VITE_PROMETHEUS_PUBLIC_PORT",
  fallback: number,
): number {
  const candidate = Number(import.meta.env[name]);
  return Number.isInteger(candidate) && candidate > 0 && candidate <= 65_535
    ? candidate
    : fallback;
}

function hostUrl(port: number, path = "/"): string {
  const hostname = window.location.hostname || "localhost";
  return `${window.location.protocol}//${hostname}:${String(port)}${path}`;
}

export const externalLinks = {
  swagger: hostUrl(
    publicPort("VITE_APP_PUBLIC_PORT", 8080),
    "/swagger-ui/index.html",
  ),
  openApi: hostUrl(publicPort("VITE_APP_PUBLIC_PORT", 8080), "/v3/api-docs"),
  prometheus: hostUrl(publicPort("VITE_PROMETHEUS_PUBLIC_PORT", 9090)),
  grafana: hostUrl(
    publicPort("VITE_GRAFANA_PUBLIC_PORT", 3000),
    "/d/distributed-rate-limiter/distributed-rate-limiter",
  ),
  liveness: "/actuator/health/liveness",
  readiness: "/actuator/health/readiness",
} as const;
