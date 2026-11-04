# ADR 0003: PostgreSQL Row Level Security

## Status
Accepted

## Decision
Tenant-scoped tables use PostgreSQL Row Level Security (RLS) as a mandatory second isolation boundary in addition to application-level tenant filtering.

## Policy model
Each request or transaction establishes an application-controlled tenant context. The datasource carries a short-lived, HMAC-SHA-256-signed capability in the PostgreSQL parameters `dokene.tenant_context` and `dokene.tenant_context_signature`; the parameters are transport, not authority.

- A tenant capability has the canonical form `tenant|<key_id>|<uuid>|<expiry>|<nonce>`, is valid for 60 seconds, and is renewed before expiry. PostgreSQL accepts custom two-part parameters without special privilege, so changing either parameter alone never grants access.
- Keys are stored in `dokene.tenant_context_signing_keys (key_id, signing_key, status, created_at, retired_at)` owned by `dokene_migration` and inaccessible to `dokene_runtime`. Keys are provisioned via parameterized JDBC migration callbacks rather than Flyway SQL literal placeholders.
- `SECURITY DEFINER` verification functions use a fixed `search_path`, resolve the active key by `key_id`, validate the canonical form, expiry, and HMAC-SHA-256 signature, and return `NULL` for every invalid input.
- **Key Rotation Lifecycle**: To rotate signing keys without downtime, provision the new key (e.g. `k2`) with status `ACTIVE`. Switch application signing to `k2`. In-flight capabilities signed with `k1` continue verifying during their 60-second validity window. After the overlap window, update `k1` to status `RETIRED`.
- **Transactional operations**: both capability parameters are propagated transaction-locally using `set_config(..., true)`. On transaction commit or rollback, the settings expire automatically. Tenant-scoped database operations should execute inside explicit transactions as the primary isolation boundary.
- **Non-transactional (auto-commit) operations**: both parameters are propagated at session scope using `set_config(..., false)`. They are explicitly reset before the connection is returned to the pool. If propagation or reset fails, the physical connection is aborted (`Connection.abort()`) to prevent dirty connection reuse.

RLS policies on protected tables restrict visible rows to that tenant and reject writes or reassignments to other tenants.

## Rules
- Protected tenant-scoped tables must enable RLS with `ENABLE ROW LEVEL SECURITY` and `FORCE ROW LEVEL SECURITY` to prevent accidental owner bypass.
- Explicit policies must be defined for `SELECT`, `INSERT`, `UPDATE`, and `DELETE` rather than relying on permissive defaults (the tenant root and workspace provisioning records are deliberate exceptions; see [Tenant root and provisioning records](#tenant-root-and-provisioning-records) and do not add the missing policies or privileges there):
  - `SELECT`: `USING (tenant_id = dokene.current_verified_tenant_id())`
  - `INSERT`: `WITH CHECK (tenant_id = dokene.current_verified_tenant_id())`
  - `UPDATE`: `USING (tenant_id = dokene.current_verified_tenant_id()) WITH CHECK (tenant_id = dokene.current_verified_tenant_id())`
  - `DELETE`: `USING (tenant_id = dokene.current_verified_tenant_id())`
- A missing, expired, malformed, or incorrectly signed capability evaluates to `NULL`, failing closed (zero read access and rejection of writes).
- Application runtime role (`dokene_runtime`) must have `NOBYPASSRLS` and must not own protected tables.
- Migration credentials (`dokene_migration`) are strictly separated from runtime credentials, and the separation is enforced by deployment, not only by roles (see [Migration job and secret delivery](#migration-job-and-secret-delivery)). Tables with `FORCE ROW LEVEL SECURITY` define an explicit migration policy `FOR ALL TO dokene_migration USING (true) WITH CHECK (true)` to permit Flyway schema and data transformations; that policy is why the migration credential must never reach the long-running backend.
- Cross-tenant isolation integration tests against PostgreSQL are mandatory for all tenant-scoped resources.
- `TenantAwareDataSource` rejects transaction-control SQL (`BEGIN`, `COMMIT`, `ROLLBACK`, savepoints, and related commands). Callers must use the corresponding `Connection` APIs so tenant-context lifecycle remains observable and fail-closed.
- JDBC decorators never unwrap to vendor objects: `unwrap` returns only the decorator when it satisfies the requested type, and `isWrapperFor` follows the same rule. A `ResultSet` is bound to the tenant active at creation and rejects access if that context changes or disappears; it may still be closed safely.
- Global tenant-membership discovery uses a separate identity-scoped signed capability (`identity|<key_id>|<uuid>|<expiry>|<nonce>`) and the narrowly granted `dokene.discover_active_tenant_memberships` function. It returns only active memberships of the authenticated identity and is not a public tenant-listing endpoint.
- RLS does not replace authorization checks in application code; both layers are mandatory.

## Migration job and secret delivery

Since issue 308 (audit M4) the migration credential exists only in a one-shot job:

- `DokeneMigrationApplication` (same image, profile `migration`) loads only Flyway and
  `TenantContextSigningKeyCallback`, connects as `dokene_migration`, applies the migrations, provisions the active
  signing key, and exits. It starts no web server, OIDC client or runtime datasource. In Compose it is the `migrate`
  service, and the backend starts only after it completed successfully.
- The long-running backend never activates that profile and holds no `DOKENE_DB_MIGRATION_*` value. Its Flyway connects
  as `dokene_runtime` and only validates the schema (`RuntimeSchemaValidationConfiguration`): a pending, failed or
  changed migration stops startup and points at the job. V34 grants the runtime role `SELECT` on the Flyway history for
  this; it holds no tenant data. `dokene.database.migrate-on-startup` exists only for integration tests, which also
  supply the migration credentials.
- The signing key and the migration password are delivered as files (`/run/secrets/dokene/<NAME>`, read through
  Spring's `configtree` import, which reads only that directory so other mounted secrets never become properties), so
  they are absent from the process environment, `/proc/<pid>/environ` and `docker inspect`. Host-run backends may
  still use environment variables.

Threat model and residual risk: a compromise of the backend process yields only `dokene_runtime` (no DDL, no RLS
bypass, no audit modification, no read of `tenant_context_signing_keys`). The backend must still hold the signing key to
sign capabilities, so such a compromise can forge tenant contexts while it lasts; recovery is the key rotation lifecycle
above (provision `k2` through the job, switch signing, retire `k1`). The migration job, the database administrator and
the secret store remain trusted.

## Append-only audit exception

ADR 0006 specializes this policy model for `audit_events`: runtime gets only
`SELECT`/`INSERT`, with no UPDATE/DELETE policies or privileges. Tenant rows retain
signed-capability isolation. A narrowly constrained global denial INSERT without
a verified tenant capability is allowed; global rows have no runtime SELECT policy. Since V34 (issue 308) triggers refuse `UPDATE`, `DELETE` and
`TRUNCATE` on `audit_events` for every role, the owning `dokene_migration` included.
See [ADR 0006](0006-durable-append-only-audit.md) for attribution, failure semantics and the break-glass procedure.

## Tenant root and provisioning records

`dokene.tenants` is the tenant root and is keyed by `id` rather than `tenant_id`; it is
not a global table for the runtime role. Since V16/V17 (issue #183):

- `tenants` forces RLS with runtime `SELECT`, `INSERT`, and `UPDATE` policies on
  `id = dokene.current_verified_tenant_id()`. Runtime has no `DELETE` privilege and may
  update only `display_name`, `status`, `updated_at`, and `version`.
- `workspace_provisioning_records` forces RLS, has only the `dokene_migration` policy, and grants
  the runtime role no direct privilege. It is reachable only through two narrowly granted
  `SECURITY DEFINER` functions that require the identity-scoped signed capability:
  - `dokene.find_workspace_provisioning_record` runs before any tenant is bound and returns only the
    authenticated identity's record for the requested idempotency key.
  - `dokene.record_workspace_provisioning` additionally requires the record's `tenant_id` to equal
    `dokene.current_verified_tenant_id()` and stores the verified identity, so a tenant context alone
    cannot record a mapping for another identity. Rejections raise `insufficient_privilege`.
- Membership discovery and the default follow-up-policy trigger keep working through their
  `SECURITY DEFINER` functions and the explicit `dokene_migration` policies.
