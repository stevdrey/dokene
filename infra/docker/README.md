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

Keycloak is defined in `compose.yaml` with an explicit image version (`quay.io/keycloak/keycloak:26.1.3`) and runs in development mode (`start-dev --import-realm`). Its HTTP port is bound to loopback only (`127.0.0.1:8081:8080`) to prevent collisions with the Spring Boot application on port 8080.

To start both PostgreSQL and Keycloak together:

```bash
docker compose up --wait
```

Keycloak automatically imports the `dokene` realm from `infra/docker/keycloak/import/dokene-realm.json`.

### BFF confidential client

The `dokene-bff` client is configured strictly as a confidential OpenID Connect client:
- Authorization Code flow enabled (`standardFlowEnabled: true`).
- Direct Access Grants (resource owner password flow) and Implicit flow are disabled.
- Client secret is never committed; Keycloak dynamically interpolates `${env.DOKENE_OIDC_CLIENT_SECRET}` from `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET` in `.env`.
- Redirect URIs are restricted to the local Spring Security callback endpoints:
  - `http://localhost:8080/login/oauth2/code/dokene`
  - `http://127.0.0.1:8080/login/oauth2/code/dokene`
- Post-logout redirect URIs are restricted to local application roots (`http://localhost:8080/`, `http://localhost:5173/`).

In accordance with Security Invariant #14, Keycloak authenticates external identity only; Keycloak roles are not authoritative for Dokene tenant membership, internal identities, or application permissions.

### Verification of discovery endpoint

Verify the imported realm and OIDC configuration:

```bash
curl -fsS http://localhost:8081/realms/dokene/.well-known/openid-configuration | jq .
```

### Development test user

For exercising local browser login against the Spring Boot BFF, the imported realm defines a development test user:
- Username: `testuser`
- Password: `testpassword` (or custom via `DOKENE_TEST_USER_PASSWORD`)
- Email: `testuser@dokene.local`

### Clean reset

To reset the local environment (database and Keycloak):

```bash
docker compose down --volumes --remove-orphans
```

### Production differences

The local Keycloak setup runs in Quarkus development mode (`start-dev`) using embedded storage and plain HTTP on loopback. Production deployments must use production mode (`start`), strict HTTPS/TLS, an external high-availability database cluster, managed secret distribution, and enterprise identity federation.
