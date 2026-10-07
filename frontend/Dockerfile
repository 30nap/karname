# syntax=docker/dockerfile:1

# ---- build: the static single-page app
FROM node:22-alpine AS build
WORKDIR /app
RUN npm install --global --no-fund --no-audit pnpm@10.28.0
COPY package.json pnpm-lock.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

# ---- runtime: nginx serves the app and proxies the API to the backend
FROM nginx:1.29-alpine
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY nginx/proxy.conf /etc/nginx/snippets/proxy.conf
COPY --from=build /app/dist /usr/share/nginx/html
# host:port of the backend, and an optional Strict-Transport-Security value (only behind HTTPS)
ENV KARNAME_BACKEND=backend:8080 \
    KARNAME_HSTS=""
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1/healthz || exit 1
