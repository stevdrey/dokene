# ADR 0022: Containerized local environment

## Status

Accepted

## Context

Local development and manual QA required each contributor to install JDK 26 and Node, export `.env` into the shell
and start PostgreSQL, Keycloak, the backend and the frontend separately. Differences between machines (a second
PostgreSQL on port 5432, stale volumes created with other passwords, wrong JDK, missing environment variables)
produced hard-to-diagnose failures such as `role "dokene_migration" does not exist` and invalid-credential logins.

## Decision

- `compose.yaml` runs every component as a container: PostgreSQL, Keycloak, the backend (multi-stage build, Temurin 26
  JRE, non-root, read-only filesystem, no Linux capabilities) and the frontend (static build served by unprivileged
  nginx).
- nginx serves the SPA on `http://localhost:5173` and proxies `/api`, `/oauth2`, `/login` and `/logout` to the backend,
  mirroring the Vite dev proxy. It presents `Host: localhost:8080` so the OIDC redirect URI registered in Keycloak is
  unchanged. The BFF contract (same-origin cookies, no tokens in the browser) is not altered.
- The OIDC issuer remains `http://localhost:${KEYCLOAK_PORT}/realms/dokene` for both browser and backend, because the
  issuer is validated by Spring Security and is part of the persisted identity mapping key. An `oidc-bridge` sidecar
  (socat) shares the backend's network namespace and forwards that localhost port to the Keycloak container.
- `scripts/dev-env.sh` is the single entry point (`up`, `down`, `status`, `logs`, `seed`, `doctor`, `reset`). It
  performs preflight checks (Docker and Compose versions, daemon, `.env` completeness, secret format, free host ports,
  stale data volumes) and generates a git-ignored `.env` with local-only secrets when none exists.
- Published ports are bound to loopback only (PostgreSQL previously listened on all interfaces).
- `DOKENE_DB_URL` for containers is derived by Compose (`postgres:5432`); the `.env` value remains for host-run backends.

## Consequences

- Contributors need only Docker. Host-run development stays supported through `up --infra-only`.
- The containerized and host-run backends use the same issuer, so identities and memberships are interchangeable.
- The Keycloak configuration remains local-only (`start --optimized`, HTTP, relaxed hostname); production differences are
  documented in `infra/docker/README.md` and are unchanged by this decision.
- The frontend image adds only basic response headers (`nosniff`, frame denial, referrer policy). A Content Security Policy
  is not set because the SPA still loads Google Fonts; tightening this belongs to a production deployment decision.
- Tests are not run during the image build; CI (`./gradlew test`, `npm test`) remains the verification gate.
