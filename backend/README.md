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
  When both key fields are supplied, they must match after canonical normalization or the request returns `400 Bad Request`.
- `GET /api/tenants/{tenantId}` verifies and returns workspace details for an authorized tenant.

Workspace provisioning is disabled by default. Operators must set `DOKENE_PROVISIONING_ENABLED=true` to enable it.
`DOKENE_PROVISIONING_ALLOWED_IDENTITIES` accepts a comma-separated list of internal identity UUIDs. When provisioning
is enabled, an empty allowlist permits every authenticated identity; a non-empty allowlist permits only the listed identities.

Tenant-scoped API operations nominate a workspace using `X-Tenant-Id: <tenant-id>`. Missing, inactive, or unauthorized
selections fail closed with `403 Forbidden` and record an audit denial.

## Customer API

All `/api/customers` requests are tenant-scoped and require `X-Tenant-Id`. State-changing requests also require
the session CSRF token. Customer payloads contain `displayName`, optional `notes`, and one to ten phone entries
shaped as `{ "number": "8888 7777", "region": "CR", "primary": true }`; exactly one must be primary. Input is
validated with its explicit two-letter country region and responses contain only normalized E.164 values.

- `POST /api/customers` creates a profile, returns `201`, and sets `ETag: "<version>"`.
- `GET /api/customers/{customerId}` returns an authorized active or archived profile with `ETag: "<version>"`.
- `PUT /api/customers/{customerId}` replaces the profile and phones; requires the current version via `If-Match: "<version>"` or JSON body `version`, and returns `200` with `ETag: "<version>"`.
- `DELETE /api/customers/{customerId}` archives the profile and requires `If-Match: "<version>"`; it returns `204`.
- `GET /api/customers` accepts `status=ACTIVE|ARCHIVED|ALL`, `name`, the paired `phone` and `region` parameters,
  opaque `cursor`, and `limit` from 1 to 100. Status defaults to `ACTIVE` and limit defaults to 50.

Duplicate phones within a tenant, including phones on archived profiles, and stale versions return an empty
`409 Conflict`. Invalid input returns `400`, unavailable resources return `404`, and authorization failures return
`403`, without exposing customer content.

Phone responses include a stable `id` used by the WhatsApp consent API. The contact-policy and profile versions are
logically independent, but adding, removing, or replacing a stable phone identity advances the policy version because
the identity set is part of the policy representation. Profile-only and primary-flag changes over the same identities
do not advance it. The contact-policy version is returned as an ETag:

- `GET /api/customers/{customerId}/contact-policy` returns current do-not-contact and consent state. Missing evidence
  is reported as `UNKNOWN`.
- `PUT /api/customers/{customerId}/contacts/{contactId}/consents/WHATSAPP` accepts `GRANTED` or `REVOKED` plus
  `CUSTOMER_VERBAL`, `CUSTOMER_WRITTEN`, or `OPERATOR_CORRECTION`, and requires the policy `If-Match`.
- `PUT /api/customers/{customerId}/do-not-contact` accepts `enabled` plus a source and requires the policy `If-Match`.
- `GET /api/customers/{customerId}/contact-eligibility?channel=WHATSAPP&contactId=...` returns deterministic reasons.
- `GET /api/customers/{customerId}/contact-policy/history` returns privacy-safe append-only evidence using an opaque
  cursor and a limit from 1 to 100.

Do-not-contact overrides consent. Clearing it does not manufacture a grant, and replacing a phone creates a new
contact identity with unknown consent. These APIs record customer intent but do not make legal-compliance claims or
authorize outbound messaging. See [ADR 0010](../docs/adr/0010-contact-consent-and-do-not-contact.md).

Flyway does not baseline a non-empty schema, validates applied migrations, and has clean disabled. The migration callback provisions the active signing key into a migration-owned database table via parameterized JDBC binding, and Migration V3 installs the verifier that makes signed, 60-second tenant capabilities authoritative for RLS; the runtime role cannot read the stored key. A startup failure on an unexpected schema must be investigated rather than bypassed.
