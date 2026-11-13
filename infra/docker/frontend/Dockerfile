# Dokene frontend image: static build served by an unprivileged nginx that also proxies the BFF routes.
# Build context: ./frontend

FROM node:24-alpine@sha256:116b800c3754b440ee0aa3ed2f20f5bf3d99a554bfd9adeea09ea8017f6e85f5 AS build
WORKDIR /app
COPY package.json package-lock.json ./
# --ignore-scripts mirrors CI and the repository supply-chain policy (.npmrc).
RUN --mount=type=cache,target=/root/.npm npm ci --ignore-scripts
COPY tsconfig.json vite.config.ts securityHeaders.ts index.html ./
COPY public public
COPY src src
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.29-alpine@sha256:0c79d56aee561a1d81c63f00eee5fb5fe29279560cdc55e91425133104c7fbe6 AS runtime
COPY --from=build /app/dist /usr/share/nginx/html
# `nginx` is an additional build context (infra/docker/frontend) declared in compose.yaml.
COPY --from=nginx nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=nginx security-headers-base.conf security-headers-spa.conf /etc/nginx/snippets/

# The base image already runs as uid 101; declaring it explicitly keeps the non-root guarantee visible to scanners (Trivy DS-0002).
USER 101

EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --retries=12 \
    CMD ["wget", "-q", "-O", "/dev/null", "http://127.0.0.1:8080/"]
