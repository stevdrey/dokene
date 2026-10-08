# Docker infrastructure

## Containerized local environment

`compose.yaml` runs the complete local stack so that every contributor runs the same configuration:

| Service | Image | Host address | Notes |
| --- | --- | --- | --- |
| `postgres` | `postgres:17-alpine` | `127.0.0.1:5432` (`DOKENE_DB_HOST_PORT`) | creates the migration/runtime roles on first start |
| `keycloak` | `infra/docker/keycloak` | `127.0.0.1:8081` (`KEYCLOAK_PORT`) | imports the `dokene` realm |
| `backend` | `infra/docker/backend` (Temurin 26 JRE, non-root, read-only) | `127.0.0.1:8080` | Flyway runs at startup |
| `oidc-bridge` | `alpine/socat` | none | shares the backend network namespace, see below |
| `frontend` | `infra/docker/frontend` (unprivileged nginx) | `127.0.0.1:5173` | static build + same-origin proxy of `/api`, `/oauth2`, `/login`, `/logout` |

All published ports are bound to loopback. The images drop all Linux capabilities, use `no-new-privileges` and
read-only root filesystems (`/tmp` is a tmpfs).

### Quick start

```bash
./scripts/dev-env.sh doctor        # preflight only: Docker/Compose, .env, free ports
./scripts/dev-env.sh up --seed     # build + start everything and create the QA workspace
./scripts/dev-env.sh status
./scripts/dev-env.sh logs backend
./scripts/dev-env.sh down          # data is kept
./scripts/dev-env.sh reset         # also deletes the PostgreSQL and Keycloak volumes
```

`up` checks that Docker and Compose are installed and running, validates `.env` (and creates it from
`.env.example` with generated local-only secrets when it does not exist), verifies that the required host ports
are free and waits for every container to become healthy. `--infra-only` starts only PostgreSQL and Keycloak for
backends/frontends run from the host (JDK 26 / Node 24):

```bash
./scripts/dev-env.sh up --infra-only
set -a; . ./.env; set +a; (cd backend && ./gradlew bootRun)
(cd frontend && npm ci && npm run dev)
```

The first image build downloads dependencies and takes several minutes; later builds use the Docker cache.

### Why `oidc-bridge`?

The browser reaches Keycloak at `http://localhost:8081`, and that exact URL is the OIDC issuer: Spring Security
validates it and the backend stores it with every identity mapping. Inside a container `localhost` is the
container itself, so the backend could not use the same URL. The `oidc-bridge` sidecar shares the backend's
network namespace and forwards `localhost:${KEYCLOAK_PORT}` to the Keycloak service. Browser and backend therefore
see an identical issuer, containers and host-run backends produce the same identities, and Keycloak needs no
special hostname configuration. The backend entrypoint waits for the discovery document before starting.

Because the sidecar lives in the backend's network namespace, it must be recreated whenever the backend is.
To apply a change to a setting consumed only by the backend (for example `DOKENE_AI_*`, `DOKENE_SESSION_*` or
`DOKENE_CORS_*`), use `./scripts/dev-env.sh restart-backend`, or recreate both services explicitly:
`docker compose up -d --force-recreate backend oidc-bridge`. Values shared with Keycloak (`KEYCLOAK_PORT`, the
OIDC client secret, `KC_*`) are also read by Keycloak, so changing them needs `./scripts/dev-env.sh up` (see
Troubleshooting for credential changes).
Recreating only `backend` can leave the sidecar on the dead namespace, and the backend then waits 180 s for the
OIDC discovery document and exits.

### Troubleshooting

- `Host port N is already in use`: another process owns the port (for PostgreSQL, often a locally installed
  server). Stop it, or for PostgreSQL set `DOKENE_DB_HOST_PORT` in `.env`; containers always reach the database
  through the Compose network, so only host-side tools are affected.
- `password authentication failed` in the backend logs, or login fails after editing `.env`: PostgreSQL and
  Keycloak fix their passwords when their volumes are first created. Restore the old `.env` or run
  `./scripts/dev-env.sh reset`.
- Rebuild after code changes with `./scripts/dev-env.sh up` (the build step runs every time and is cached).

## Legacy notes

Container definitions for production deployment will also live here as the application becomes deployable.

Security baseline for runtime images:

- non-root user,
- minimal base image,
- read-only filesystem where practical,
- no embedded secrets,
- dropped Linux capabilities unless explicitly required,
- separate build and runtime stages.

Local PostgreSQL, Keycloak, the backend and the frontend are defined in the root `compose.yaml`.

## Local database roles and clean startup

Copy `.env.example` from the repository root to `.env`, fill every blank value with local-only values, and generate distinct bootstrap, migration, and runtime passwords. `DOKENE_DB_PASSWORD` and `DOKENE_DB_RUNTIME_PASSWORD` must contain the same local runtime password. Do not commit `.env`.

The PostgreSQL initialization script creates two non-superuser roles:

- `dokene_migration` owns the `dokene` schema and can run Flyway DDL.
- `dokene_runtime` has only `USAGE` plus required table DML privileges. It owns no application tables and has `NOBYPASSRLS`.

For a clean local verification, remove only the disposable Compose volume, start PostgreSQL, then launch the backend with the variables from `.env` exported (host-run backend; the containerized backend is started by `./scripts/dev-env.sh up`):

```bash
docker compose down --volumes --remove-orphans
docker compose up --wait postgres
set -a; . ./.env; set +a
(cd backend && ./gradlew bootRun)
```

Flyway must report applying `V1__create_tenant_foundation.sql`; Hibernate then validates the resulting schema. Stop `bootRun` after startup. Future migrations use `V<version>__<snake_case_description>.sql`, are immutable once shared, and must include deliberate ownership, grant, and RLS-policy review.

## Local Keycloak OIDC provider

A reproducible local Keycloak service is provided for OIDC BFF authorization-code authentication without requiring manual administration console configuration.

### Startup and realm import

Keycloak is built from `infra/docker/keycloak/Dockerfile` on top of `quay.io/keycloak/keycloak:26.7.3` with Quarkus ahead-of-time augmentation (`kc.sh build`) and runs in optimized mode (`start --optimized --http-enabled=true --hostname-strict=false --import-realm`). Its HTTP port is bound to loopback only (`127.0.0.1:${KEYCLOAK_PORT:-8081}:8080`) to prevent collisions with the Spring Boot application on port 8080. Local bootstrap administrator credentials are configured via `KC_BOOTSTRAP_ADMIN_USERNAME` and `KC_BOOTSTRAP_ADMIN_PASSWORD` in `.env`.

To start only PostgreSQL and Keycloak (for a backend run from the host):

```bash
docker compose up -d --wait postgres keycloak
```

`docker compose up` without service names now also builds and starts the backend and frontend containers.

Keycloak automatically imports the `dokene` realm from `infra/docker/keycloak/import/dokene-realm.json`.

### BFF confidential client

The `dokene-bff` client is configured strictly as a confidential OpenID Connect client:
- Authorization Code flow enabled (`standardFlowEnabled: true`).
- Direct Access Grants (resource owner password flow) and Implicit flow are disabled.
- Client secret is never committed; Keycloak's container entrypoint safely escapes and interpolates `${env.DOKENE_OIDC_CLIENT_SECRET}` from `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET` in `.env` into the realm definition, safely supporting characters such as `/` (from `openssl rand -base64 32`), `&`, `\`, and quotes without shell or JSON corruption.
- Redirect URIs are restricted to the local Spring Security callback endpoints:
  - `http://localhost:8080/login/oauth2/code/dokene`
  - `http://127.0.0.1:8080/login/oauth2/code/dokene`
- Post-logout redirect URIs are restricted to local application roots (`http://localhost:8080/`, `http://localhost:5173/`).
- The discovery endpoint `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_DOKENE_ISSUER_URI` dynamically tracks `KEYCLOAK_PORT` (`http://localhost:${KEYCLOAK_PORT:-8081}/realms/dokene`) so changing `KEYCLOAK_PORT` in `.env` automatically keeps Spring's discovery target synchronized.

In accordance with Security Invariant #14, Keycloak authenticates external identity only; Keycloak roles are not authoritative for Dokene tenant membership, internal identities, or application permissions.

### Verification of discovery endpoint

Verify the imported realm and OIDC configuration:

```bash
set -a; [ -f .env ] && . ./.env; set +a
curl -fsS "http://localhost:${KEYCLOAK_PORT:-8081}/realms/dokene/.well-known/openid-configuration" | jq .
```

### Development synthetic test identities & QA seeding

For exercising local browser login and multi-tenant RBAC validation against the Spring Boot BFF, the imported realm defines three synthetic test users (sharing `DOKENE_TEST_USER_PASSWORD`, default: `testpassword`):

| Username | Email | Intended Role | Capabilities |
| :--- | :--- | :--- | :--- |
| `testuser` | `testuser@dokene.local` | `OWNER` | Full workspace provisioning, configuration, and membership administration (`MEMBERSHIP_INVITE`, `MEMBERSHIP_ROLE_UPDATE`, `MEMBERSHIP_REVOKE`). |
| `testoperator` | `testoperator@dokene.local` | `OPERATOR` | Operational permissions (customer and purchase read/write, follow-up evaluations and dispositions), without membership or workspace administration. |
| `testviewer` | `testviewer@dokene.local` | `VIEWER` | Read-only access across all domain resources (customers, purchases, follow-ups). State-changing actions and controls are forbidden. |

To automatically establish the canonical multi-tenant workspace (`QA Café Norte`) and assign memberships across these synthetic identities, run the dev-seed fixture. Note that workspace provisioning is disabled by default in Dokene; make sure your local backend is started with `DOKENE_PROVISIONING_ENABLED=true` (in your `.env` file):

```bash
./scripts/seed-local-qa.sh
```

To run the fixture and immediately verify all RBAC boundaries across all identities:

```bash
./scripts/seed-local-qa.sh --verify
```

### Clean reset

To reset the local environment (database and Keycloak):

```bash
docker compose down --volumes --remove-orphans
```

### Production differences

While the local environment uses `start --optimized` with embedded storage (`dev-file`), plain HTTP on loopback (`--http-enabled=true`), and relaxed hostname verification (`--hostname-strict=false`), production deployments must enforce:

- strict HTTPS/TLS with trusted CA certificates;
- strict hostname validation and edge reverse-proxy header handling;
- an external high-availability database cluster (such as managed PostgreSQL);
- managed secret distribution and rotation rather than local environment interpolation;
- production-grade enterprise identity federation and audit logging rather than synthetic local realms and development users.
