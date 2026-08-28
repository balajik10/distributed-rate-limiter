# syntax=docker/dockerfile:1.7
FROM node:24.20.0-alpine3.23@sha256:0388af2af070cd4736a1567cfed02469ba117848845b4165d87a333edb53d2ca AS builder

WORKDIR /workspace
ARG VITE_APP_PUBLIC_PORT=8080
ARG VITE_GRAFANA_PUBLIC_PORT=3000
ARG VITE_PROMETHEUS_PUBLIC_PORT=9090
ENV VITE_APP_PUBLIC_PORT=${VITE_APP_PUBLIC_PORT} \
    VITE_GRAFANA_PUBLIC_PORT=${VITE_GRAFANA_PUBLIC_PORT} \
    VITE_PROMETHEUS_PUBLIC_PORT=${VITE_PROMETHEUS_PUBLIC_PORT}
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.28.2-alpine3.23@sha256:7377697a821c131a924a7105fafbe7414db4e9fcc77a6f08f776f33f141ec3f8 AS runtime

COPY --chown=101:101 nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --chown=101:101 nginx/proxy-params.conf /etc/nginx/includes/proxy-params.conf
COPY --chown=101:101 nginx/security-headers.conf /etc/nginx/includes/security-headers.conf
COPY --from=builder --chown=101:101 /workspace/dist /usr/share/nginx/html/console

USER 101:101
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=6 \
  CMD ["wget", "--quiet", "--spider", "http://127.0.0.1:8080/healthz"]
