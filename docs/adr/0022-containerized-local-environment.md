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
- PostgreSQL and Keycloak run with the same restrictions as the other services (issue 319, audit L14): no Linux
  capabilities, `no-new-privileges`, a non-root user and a read-only root filesystem. PostgreSQL starts directly as
  the image's `postgres` user, because the stock root start needs `CHOWN`, `SETUID` and `SETGID`. PostgreSQL keeps
  its data volume; Keycloak keeps its data on a tmpfs. The writable paths of every service are listed in
  `infra/docker/README.md` and are not repeated here. A named Keycloak volume was not chosen: `--import-realm` skips a realm that
  already exists, so a changed client secret, test password or realm template would stop applying until a reset.
- nginx serves the SPA on `http://localhost:5173` and proxies `/api`, `/oauth2`, `/login` and `/logout` to the backend,
  mirroring the Vite dev proxy. It presents `Host: localhost:8080` so the OIDC redirect URI registered in Keycloak is
  unchanged. The BFF contract (same-origin cookies, no tokens in the browser) is not altered.
- The OIDC issuer remains `http://localhost:${KEYCLOAK_PORT}/realms/dokene` for both browser and backend, because the
  issuer is validated by Spring Security and is part of the persisted identity mapping key. An `oidc-bridge` sidecar
  (socat) forwards that localhost port to the Keycloak container and owns the network namespace the backend joins
  (`network_mode: service:oidc-bridge`), together with the published port 8080 and the `backend` DNS alias. The
  sidecar owns the namespace so that recreating the backend, the routine operation, does not orphan it. The sidecar
  has a healthcheck (it must resolve its own service name through the Compose DNS, as an absolute name because BusyBox `nslookup` appends the host DNS search domain to relative ones) and the backend waits for it to be healthy, so a bridge that is
  attached to no network is reported at once. A failed `up` removes the containers it left unstarted, and `up`
  recreates a bridge found without a network, so a failed start never needs a manual `down`.
- `scripts/dev-env.sh` is the single entry point (`up`, `down`, `restart-backend`, `status`, `logs`, `seed`, `doctor`, `reset`). It
  performs preflight checks (Docker and Compose versions, daemon, `.env` completeness, secret format, free host ports,
  stale data volumes) and generates a git-ignored `.env` with local-only secrets when none exists. A host port counts as taken when `docker ps` reports a TCP mapping that covers it (including
  `start-end` ranges; UDP-only mappings are ignored), or when `ss`/`nc` see a listener. The mapping check is required because
  with `userland-proxy=false` Docker publishes through NAT and opens no listening socket, yet still rejects a second
  mapping; `ss`/`nc` need no privileges and see root-owned `docker-proxy` sockets. `lsof`, `ss -p` and `docker ps`
  also name the owner (`lsof` run as a regular user on Linux cannot see root-owned sockets), and the advice differs
  for containers and host processes.
- Published ports are bound to loopback only (PostgreSQL previously listened on all interfaces).
- `DOKENE_DB_URL` for containers is derived by Compose (`postgres:5432`); the `.env` value remains for host-run backends.

## Consequences

- Contributors need only Docker. Host-run development stays supported through `up --infra-only`.
- The containerized and host-run backends use the same issuer, so identities and memberships are interchangeable.
- The Keycloak configuration remains local-only (`start --optimized`, HTTP, relaxed hostname); production differences are
  documented in `infra/docker/README.md` and are unchanged by this decision.
- The frontend image sets response security headers, including a Content Security Policy and Permissions-Policy
  (see [ADR 0023](0023-spa-and-bff-response-security-headers.md)).
- Keycloak state (users, sessions, signing keys) is held in memory and ends when the container stops; every start
  imports the realm again. The test users keep their workspace memberships because their ids are fixed in the realm
  template. Before issue 319 that state lived in the container's writable layer and ended when the container was
  removed. The writable paths and the remaining exceptions are listed in `infra/docker/README.md`.
- Tests are not run during the image build; CI (`./gradlew test`, `npm test`) remains the verification gate.
