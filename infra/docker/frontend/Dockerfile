# syntax=docker/dockerfile:1
# Dokene frontend image: static build served by an unprivileged nginx that also proxies the BFF routes.
# Build context: ./frontend

FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
# --ignore-scripts mirrors CI and the repository supply-chain policy (.npmrc).
RUN --mount=type=cache,target=/root/.npm npm ci --ignore-scripts
COPY tsconfig.json vite.config.ts index.html ./
COPY public public
COPY src src
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.29-alpine AS runtime
COPY --from=build /app/dist /usr/share/nginx/html
# `nginx` is an additional build context (infra/docker/frontend) declared in compose.yaml.
COPY --from=nginx nginx.conf /etc/nginx/conf.d/default.conf

# The base image already runs as uid 101; declaring it explicitly keeps the non-root guarantee visible to scanners (Trivy DS-0002).
USER 101

EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --retries=12 \
    CMD ["wget", "-q", "-O", "/dev/null", "http://127.0.0.1:8080/"]
