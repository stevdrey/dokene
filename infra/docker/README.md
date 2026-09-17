# Docker infrastructure

Container definitions will live here as the application becomes deployable.

Security baseline for runtime images:

- non-root user,
- minimal base image,
- read-only filesystem where practical,
- no embedded secrets,
- dropped Linux capabilities unless explicitly required,
- separate build and runtime stages.

Local PostgreSQL is currently defined in the root `compose.yaml`.

## Local database roles and clean startup

Copy `.env.example` from the repository root to `.env`, fill every blank value with local-only values, and generate distinct bootstrap, migration, and runtime passwords. `DOKENE_DB_PASSWORD` and `DOKENE_DB_RUNTIME_PASSWORD` must contain the same local runtime password. Do not commit `.env`.

The PostgreSQL initialization script creates two non-superuser roles:

- `dokene_migration` owns the `dokene` schema and can run Flyway DDL.
- `dokene_runtime` has only `USAGE` plus required table DML privileges. It owns no application tables and has `NOBYPASSRLS`.

For a clean local verification, remove only the disposable Compose volume, start PostgreSQL, then launch the backend with the variables from `.env` exported:

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

To start both PostgreSQL and Keycloak together:

```bash
docker compose up --wait
```

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
