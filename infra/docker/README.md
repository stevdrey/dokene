# Docker infrastructure

## Containerized local environment

`compose.yaml` runs the complete local stack so that every contributor runs the same configuration:

| Service | Image | Host address | Notes |
| --- | --- | --- | --- |
| `postgres` | `postgres:17-alpine` (runs as the `postgres` user, read-only) | `127.0.0.1:5432` (`DOKENE_DB_HOST_PORT`) | creates the migration/runtime roles on first start |
| `keycloak` | `infra/docker/keycloak` (non-root, read-only) | `127.0.0.1:8081` (`KEYCLOAK_PORT`) | imports the `dokene` realm at every container start |
| `migrate` | backend image, one-shot | none | applies Flyway migrations as `dokene_migration`, then exits (issue 308) |
| `backend` | `infra/docker/backend` (Temurin 26 JRE, non-root, read-only) | `127.0.0.1:8080` | starts after `migrate` succeeded; validates the schema as `dokene_runtime` |
| `oidc-bridge` | `alpine/socat` | `127.0.0.1:8080` (published for the backend) | owns the network namespace the backend joins, see below |
| `frontend` | `infra/docker/frontend` (unprivileged nginx) | `127.0.0.1:5173` | static build + same-origin proxy of `/api`, `/oauth2`, `/login`, `/logout`, `/webhooks` |

The migration credential reaches only `migrate`. The tenant-context signing key and the migration password are
Compose secrets that `./scripts/dev-env.sh up` writes from `.env` into the git-ignored `.secrets/` directory (created
with mode 0700; `DOKENE_SECRETS_DIR` may name another directory of your own with mode 0700) and Compose
mounts under `/run/secrets/dokene/` (read through Spring's `configtree` import, which reads only that directory), so
they are not in any container's environment. When a secret changes, `up` recreates `migrate` and `backend`. On an upgrade, `up` runs `migrate` again before the new backend; a
backend that finds a pending migration refuses to start and points at `./scripts/dev-env.sh logs migrate`.

All published ports are bound to loopback. Every service drops all Linux capabilities, sets `no-new-privileges`, runs
as a non-root user and has a read-only root filesystem. Nothing else is writable:

| Service | Writable path | Kind | Holds |
| --- | --- | --- | --- |
| `postgres` | `/var/lib/postgresql/data` | named volume `dokene-postgres` | the database; kept until `reset` |
| `postgres` | `/var/run/postgresql` | tmpfs, 1 MiB, owned by uid 70 | Unix socket and lock file |
| `postgres` | `/tmp` | tmpfs, 16 MiB | unused by startup; for large here-documents in init scripts or `exec` sessions |
| `keycloak` | `/opt/keycloak/data` | tmpfs, 256 MiB, mode 0700, owned by uid 1000 | dev-file database, transaction logs, the rendered realm import |
| `keycloak` | `/tmp` | tmpfs, 64 MiB | Vert.x cache, JVM performance data |
| `migrate`, `backend` | `/tmp` | tmpfs | temporary files |
| `frontend` | `/tmp` | tmpfs, 128 MiB | nginx request bodies (issue 306) |
| every service | `/dev/shm` | tmpfs, 64 MiB, `noexec` (Docker default) | PostgreSQL dynamic shared memory segments; unused by the other services |

`oidc-bridge` has no writable path besides `/dev/shm`. `read_only` does not cover `/dev/shm`: Docker mounts it in
every container, and PostgreSQL needs it. The `/dev` tmpfs is writable by root only, and no service runs as root.

PostgreSQL starts directly as the image's `postgres` user (`user: "70:70"`). The stock entrypoint starts as root only
to chown the data directory and step down, which needs `CHOWN`, `SETUID` and `SETGID`; with every capability dropped
that start fails (`failed switching to 'postgres': operation not permitted`). A volume created before this change is
already owned by uid 70 and is reused as it is. `DOKENE_POSTGRES_UID` (default 70) sets both `user:` and the owner
of the `/var/run/postgresql` tmpfs. If you replace the image with a Debian-based `postgres` tag, set it to that
image's `postgres` user (999).

The home directories of both users are on the read-only root. `psql` keeps its history in `/tmp`
(`PSQL_HISTORY`). `kcadm.sh` writes its login to `~/.keycloak/kcadm.config` by default, which fails; pass
`--config /tmp/kcadm.config` to `kcadm.sh config credentials` and to every later `kcadm.sh` call in a
`docker compose exec keycloak` session.

Keycloak state lives in memory: users, sessions and signing keys last until the container stops, and each start
imports the realm again from `infra/docker/keycloak/import`. The test users have fixed ids in that file, so their
workspace memberships in PostgreSQL stay valid; a restart of Keycloak ends the open sessions, and users or settings
added through the admin console are lost. The rendered import contains the client secret and the test password, so
it is no longer written to the container's disk layer.

Remaining exceptions (issue 319):

- The PostgreSQL bootstrap role is a superuser inside the database and its password is in the container
  environment, as are the two role passwords the init script needs on first start.
- Keycloak listens on HTTP with `--hostname-strict=false` and the embedded dev-file database; this stack is local
  only.
- `/dev/shm` stays writable in every container (see the table above).
- No user namespace remapping, custom seccomp or AppArmor profile, and no memory, CPU or pid limits are set; Docker's
  default seccomp profile applies.

### Quick start

```bash
./scripts/dev-env.sh doctor        # preflight only: Docker/Compose, .env, free ports
./scripts/dev-env.sh up --seed     # build + start everything and create the QA workspace
./scripts/dev-env.sh status
./scripts/dev-env.sh logs backend
./scripts/dev-env.sh down          # the database is kept; Keycloak imports its realm again on the next start
./scripts/dev-env.sh reset         # also deletes the PostgreSQL volume
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
container itself, so the backend could not use the same URL. The `oidc-bridge` sidecar forwards `localhost:${KEYCLOAK_PORT}` to the Keycloak service, and the backend runs in
the sidecar's network namespace (`network_mode: service:oidc-bridge`). Browser and backend therefore see an
identical issuer, containers and host-run backends produce the same identities, and Keycloak needs no special
hostname configuration. The backend entrypoint waits for the discovery document before starting.

The sidecar owns the namespace (and therefore the published port `8080` and the `backend` DNS alias used by the
frontend) because the backend is the service that gets recreated routinely. Recreating only the backend, for
example `docker compose up -d backend` after changing `DOKENE_AI_*`, is safe: the new container rejoins the
sidecar's live namespace.

The reverse is not safe: if `oidc-bridge` is recreated on its own, the backend stays attached to the dead namespace,
never finds the OIDC discovery document, and exits after 180 s. Recover with `./scripts/dev-env.sh restart-backend`
(or `docker compose up -d --force-recreate --no-deps oidc-bridge backend`), or run `./scripts/dev-env.sh up`. A
full `up` is also required after changing values that Keycloak reads too (`KEYCLOAK_PORT`, the OIDC client secret,
`KC_*`); see Troubleshooting for credential changes.

`oidc-bridge` has a healthcheck that resolves its own service name, and the backend waits for it to be healthy. If `up` fails
while creating the bridge (for example `Bind for 127.0.0.1:8080 failed`), Docker leaves it attached to no network;
`dev-env.sh` removes containers left unstarted by such a failure and, on the next `up`, recreates a bridge found
without a network, so freeing the port and rerunning `up` is enough (no `down` needed).

### Response security headers

nginx sets a `Content-Security-Policy` and `Permissions-Policy` on the SPA (snippets in
`infra/docker/frontend/security-headers-*.conf`); the BFF routes get theirs from Spring Security and nginx passes them
through (rationale in [ADR 0023](../../docs/adr/0023-spa-and-bff-response-security-headers.md)).
The SPA policy allows only same-origin resources, so assets must be self-hosted. Any production edge that serves the
SPA must replicate these headers. Verify with `./scripts/tests/nginx-headers-smoke.sh` (Docker only). For host-run
development, `npm run dev` has no CSP; use `npm run build && npm run preview` (same headers) to check violations.

### Troubleshooting

- `Host port N is already in use`: another process owns the port (for PostgreSQL, often a locally installed
  server). Stop it, or for PostgreSQL set `DOKENE_DB_HOST_PORT` in `.env`; containers always reach the database
  through the Compose network, so only host-side tools are affected.
- `password authentication failed` in the backend logs after editing `.env`: PostgreSQL fixes its passwords when
  its volume is first created. Restore the old `.env` or run `./scripts/dev-env.sh reset`. Keycloak reads the client
  secret and the test user passwords at every container start, so `./scripts/dev-env.sh up` is enough for those.
- A test user login is refused after pulling issue 314: run `./scripts/dev-env.sh up`. It adds the missing
  `DOKENE_TEST_*_PASSWORD` values to `.env` and recreates Keycloak, which imports the realm with them; the old shared
  `DOKENE_TEST_USER_PASSWORD` no longer works and can be deleted from `.env`.
- `DOKENE_TEST_<ROLE>_PASSWORD in .env must be at least 12 characters`: the realm password policy is
  `length(12) and notUsername and notEmail`. Use a longer value, or delete the line and let `up` generate one.
- Smoke-test the tooling without Docker running: `./scripts/tests/dev-env-smoke.sh`.
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

- `dokene_migration` owns the `dokene` schema and can run Flyway DDL. Only the migration job uses it.
- `dokene_runtime` has only `USAGE` plus required table DML privileges. It owns no application tables and has `NOBYPASSRLS`.

For a clean local verification, remove only the disposable Compose volume, start PostgreSQL, run the migration job, then launch the backend with the variables from `.env` exported (host-run backend; the containerized stack is started by `./scripts/dev-env.sh up`):

```bash
docker compose down --volumes --remove-orphans
docker compose up --wait postgres
set -a; . ./.env; set +a
(cd backend && ./gradlew migrateDb && ./gradlew bootRun)
```

`migrateDb` must report applying `V1__create_tenant_foundation.sql` and the later migrations; the backend then validates the Flyway history and Hibernate validates the resulting schema. The backend never migrates: without `migrateDb` it refuses to start. Stop `bootRun` after startup. Future migrations use `V<version>__<snake_case_description>.sql`, are immutable once shared, and must include deliberate ownership, grant, and RLS-policy review.

## Local Keycloak OIDC provider

A reproducible local Keycloak service is provided for OIDC BFF authorization-code authentication without requiring manual administration console configuration.

### Startup and realm import

Keycloak is built from `infra/docker/keycloak/Dockerfile` on top of `quay.io/keycloak/keycloak:26.7.3` with Quarkus ahead-of-time augmentation (`kc.sh build`) and runs in optimized mode (`start --optimized --http-enabled=true --hostname-strict=false --import-realm`). Its HTTP port is bound to loopback only (`127.0.0.1:${KEYCLOAK_PORT:-8081}:8080`) to prevent collisions with the Spring Boot application on port 8080. Local bootstrap administrator credentials are configured via `KC_BOOTSTRAP_ADMIN_USERNAME` and `KC_BOOTSTRAP_ADMIN_PASSWORD` in `.env`.

To start only PostgreSQL and Keycloak (for a backend run from the host):

```bash
docker compose up -d --wait postgres keycloak
```

`docker compose up` without service names now also builds and starts the backend and frontend containers.

This command does not run the `.env` checks of `dev-env.sh`. Keycloak refuses to start while one of
`DOKENE_TEST_OWNER_PASSWORD`, `DOKENE_TEST_OPERATOR_PASSWORD` and `DOKENE_TEST_VIEWER_PASSWORD` is empty (issue 314),
so run `./scripts/dev-env.sh up` once first, which generates the missing values in `.env` (`doctor` generates them
too, after asking).

Keycloak automatically imports the `dokene` realm from `infra/docker/keycloak/import/dokene-realm.json`.

Since issue 319 the Compose Keycloak keeps its data in memory and imports that file at every container start, so
edits to it and the test user passwords in `.env` reach the stack on the next `./scripts/dev-env.sh up`.
`./scripts/tests/keycloak-realm-config-test.sh` checks the realm file itself without Docker.

`scripts/keycloak-apply-realm-settings.sh` is for a Keycloak that keeps its realm across starts, where the import
skips a realm that already exists; `dev-env.sh` does not call it. It applies the session revocation settings (issue
307), S256 PKCE on `dokene-bff` and the password policy (issue 314), then sets each test user's password from the
Keycloak container's environment. It is idempotent, needs `jq`, and never prints a secret.
`./scripts/keycloak-apply-realm-settings.sh --check` reports config drift without changing anything (passwords cannot
be checked).

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

For exercising local browser login and multi-tenant RBAC validation against the Spring Boot BFF, the imported realm defines three synthetic test users. Each has its own password, which `./scripts/dev-env.sh` generates into `.env` (`openssl rand -hex 16`); there is no default, and knowing one user's password does not open another account. Read a password with, for example, `grep DOKENE_TEST_OWNER_PASSWORD .env`. A custom value must be at least 12 characters. Use plain characters or single quotes: preflight refuses a `$`, a backslash or an inline ` #` comment that Compose would not pass on as written.

| Username | Email | Intended Role | Password in `.env` | Capabilities |
| :--- | :--- | :--- | :--- | :--- |
| `testuser` | `testuser@dokene.local` | `OWNER` | `DOKENE_TEST_OWNER_PASSWORD` | Full workspace provisioning, configuration, and membership administration (`MEMBERSHIP_INVITE`, `MEMBERSHIP_ROLE_UPDATE`, `MEMBERSHIP_REVOKE`). |
| `testoperator` | `testoperator@dokene.local` | `OPERATOR` | `DOKENE_TEST_OPERATOR_PASSWORD` | Operational permissions (customer and purchase read/write, follow-up evaluations and dispositions), without membership or workspace administration. |
| `testviewer` | `testviewer@dokene.local` | `VIEWER` | `DOKENE_TEST_VIEWER_PASSWORD` | Read-only access across all domain resources (customers, purchases, follow-ups). State-changing actions and controls are forbidden. |

Keycloak imports these passwords from `.env` at every container start, so a password changed in the Keycloak admin console lasts only until Keycloak restarts. Change it in `.env` instead.

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

### Local template versus shared deployments

`dokene-realm.json` is a local template. It is the natural starting point for a shared or staging realm, but it is not something to deploy as is. No shared deployment exists yet; when one is planned, its realm needs its own process (issue 314 follow up).

Carry these settings over; they are what the BFF and the security audit rely on, and `scripts/tests/keycloak-realm-config-test.sh` guards them in the template:

- `dokene-bff` as a confidential client (client secret, not public) with exact redirect URIs;
- `pkce.code.challenge.method=S256` on `dokene-bff`, so the provider refuses a login without S256 PKCE;
- no implicit flow and no direct access grants;
- self registration off, brute force protection on, `sslRequired: external`;
- the back-channel logout attributes and the SSO max lifespan from ADR 0007;
- at least the local password policy, `length(12) and notUsername and notEmail`.

Local only; leave these out of a shared realm or replace them:

- the three test users and their imported passwords (`testuser`, `testoperator`, `testviewer`);
- secrets interpolated from `.env` (client secret, bootstrap admin, test passwords) instead of a secret manager;
- test passwords imported again at every start, and `scripts/keycloak-apply-realm-settings.sh` setting them;
- plain HTTP on loopback, relaxed hostname checks and embedded storage (see Production differences above).

Still to decide for a shared realm: password history, a breached password blocklist, MFA for OWNER accounts, and how the admin console is exposed.
