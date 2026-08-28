import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

const backendTarget = process.env.VITE_DEV_BACKEND ?? "http://127.0.0.1:8080";

export default defineConfig({
  base: "/console/",
  plugins: [react(), tailwindcss()],
  build: {
    sourcemap: false,
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": { target: backendTarget, changeOrigin: false },
      "/actuator/health/liveness": {
        target: backendTarget,
        changeOrigin: false,
      },
      "/actuator/health/readiness": {
        target: backendTarget,
        changeOrigin: false,
      },
    },
  },
});
