# Dokene Backend

Spring Boot modular monolith.

Planned domain/module boundaries:

- `identity` — authentication-facing identity model
- `tenant` — tenant context, membership, roles, permissions
- `customer` — customer profile and consent state
- `purchase` — purchase/history signals
- `followup` — scheduling and follow-up decision workflow
- `template` — message-template lifecycle and versions
- `messaging` — message state machine and outbound ports
- `ai` — provider abstraction and structured recommendations
- `integration` — external provider adapters
- `audit` — append-only security/business audit events
- `security` — policy enforcement and cross-cutting security controls

The domain core must not depend directly on concrete AI or messaging providers.

## Database migrations

Production schema changes live in `src/main/resources/db/migration` and are applied by Flyway. Use the immutable naming convention `V<version>__<snake_case_description>.sql`; never edit a migration that has been applied outside a disposable local database. Every migration must review object ownership, explicit runtime grants, and any required Row Level Security policy changes.

The `dokene_migration` role owns the `dokene` schema and applies DDL. The application connects as `dokene_runtime`, which has only the DML grants required by the current service and does not have `BYPASSRLS`. Set the following environment variables before starting the backend:

- `DOKENE_DB_URL`, `DOKENE_DB_USERNAME`, `DOKENE_DB_PASSWORD` for runtime traffic;
- `DOKENE_DB_MIGRATION_USERNAME`, `DOKENE_DB_MIGRATION_PASSWORD` for Flyway.
- `DOKENE_TENANT_CONTEXT_SIGNING_KEY`, a 64-character hexadecimal encoding of 32 random bytes. Generate it with `openssl rand -hex 32`; keep the value out of source control.
- `DOKENE_TENANT_CONTEXT_KEY_ID`, an identifier for the active signing key (defaults to `default`).

## OIDC browser authentication

Dokene uses Spring Security's OIDC authorization-code flow and a server-side HTTP session. Configure one
provider with standard Spring Boot properties; the `.env.example` uses the registration ID `dokene`.
At minimum set the client ID, client secret, scopes including `openid`, redirect URI, and provider issuer URI.
Register `{baseUrl}/login/oauth2/code/dokene` as the provider callback and initiate login at
`/oauth2/authorization/dokene`. A successful callback redirects to `GET /api/session`.

The callback validates authorization state and the provider's OIDC response through Spring Security. A valid
issuer and subject are atomically mapped to a stable internal `IdentityId`; email and provider role claims are
never used for account linking or tenant authorization. Tokens remain in server-side authentication/session
state and must not be logged or copied to browser storage.

`GET /api/session` returns `authenticated`, the internal `identityId`, and the session CSRF token. Unauthenticated
or expired sessions receive `401`. Send that token as `X-CSRF-TOKEN` for state-changing requests. `POST /logout`
requires CSRF, invalidates the application session, deletes `JSESSIONID`, and returns `204`. The session defaults
to 30 minutes. Cookies are `HttpOnly`, `Secure`, and `SameSite=Lax`; set `DOKENE_SESSION_COOKIE_SECURE=false` only
for local HTTP development.

CORS rejects cross-origin credentialed traffic by default. `DOKENE_CORS_ALLOWED_ORIGINS` may contain a
comma-separated exact allowlist (for example `http://localhost:5173` locally); wildcard origins are not used.
The frontend should send cookies with `credentials: include` and must keep OIDC/session values out of
`localStorage` and other browser-persistent storage.

## Workspace provisioning and tenant selection

Once authenticated, operators use global endpoints under `/api/tenants` to manage and select workspaces:

- `GET /api/tenants` lists all active workspaces where the operator has an active membership. Inactive or
  suspended workspaces and non-active memberships are excluded server-side.
- `POST /api/tenants` provisions a new workspace and initial `OWNER` membership atomically. Provisioning requires an
  idempotency key passed in the `Idempotency-Key` header or `idempotencyKey` body field, plus `displayName`. Replaying
  with the same key and name returns `200 OK` with the existing workspace; conflicting payloads return `409 Conflict`.
- `GET /api/tenants/{tenantId}` verifies and returns workspace details for an authorized tenant.

Tenant-scoped API operations nominate a workspace using `X-Tenant-Id: <tenant-id>`. Missing, inactive, or unauthorized
selections fail closed with `403 Forbidden` and record an audit denial.

Flyway does not baseline a non-empty schema, validates applied migrations, and has clean disabled. The migration callback provisions the active signing key into a migration-owned database table via parameterized JDBC binding, and Migration V3 installs the verifier that makes signed, 60-second tenant capabilities authoritative for RLS; the runtime role cannot read the stored key. A startup failure on an unexpected schema must be investigated rather than bypassed.
