/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_APP_PUBLIC_PORT?: string;
  readonly VITE_GRAFANA_PUBLIC_PORT?: string;
  readonly VITE_PROMETHEUS_PUBLIC_PORT?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
