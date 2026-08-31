# ADR 0003: PostgreSQL Row Level Security

## Status
Accepted

## Decision
Tenant-scoped tables use PostgreSQL Row Level Security (RLS) as a mandatory second isolation boundary in addition to application-level tenant filtering.

## Policy model
Each request or transaction establishes an application-controlled tenant context. The datasource carries a short-lived, HMAC-SHA-256-signed capability in the PostgreSQL parameters `dokene.tenant_context` and `dokene.tenant_context_signature`; the parameters are transport, not authority.

- A tenant capability has the canonical form `tenant|uuid|expiry|nonce`, is valid for 60 seconds, and is renewed before expiry. PostgreSQL accepts custom two-part parameters without special privilege, so changing either parameter alone never grants access.
- Migration V3 stores the 32-byte signing key in a migration-owned table that `dokene_runtime` cannot read. `SECURITY DEFINER` verification functions use a fixed `search_path`, validate the canonical form, expiry, and HMAC, and return `NULL` for every invalid input.
- **Transactional operations**: both capability parameters are propagated transaction-locally using `set_config(..., true)`. On transaction commit or rollback, the settings expire automatically.
- **Non-transactional (auto-commit) operations**: both parameters are propagated at session scope using `set_config(..., false)`. They are explicitly reset before the connection is returned to the pool. If propagation or reset fails, the physical connection is aborted (`Connection.abort()`) to prevent dirty connection reuse.

RLS policies on protected tables restrict visible rows to that tenant and reject writes or reassignments to other tenants.

## Rules
- Protected tenant-scoped tables must enable RLS with `ENABLE ROW LEVEL SECURITY` and `FORCE ROW LEVEL SECURITY` to prevent accidental owner bypass.
- Explicit policies must be defined for `SELECT`, `INSERT`, `UPDATE`, and `DELETE` rather than relying on permissive defaults:
  - `SELECT`: `USING (tenant_id = dokene.current_verified_tenant_id())`
  - `INSERT`: `WITH CHECK (tenant_id = dokene.current_verified_tenant_id())`
  - `UPDATE`: `USING (tenant_id = dokene.current_verified_tenant_id()) WITH CHECK (tenant_id = dokene.current_verified_tenant_id())`
  - `DELETE`: `USING (tenant_id = dokene.current_verified_tenant_id())`
- A missing, expired, malformed, or incorrectly signed capability evaluates to `NULL`, failing closed (zero read access and rejection of writes).
- Application runtime role (`dokene_runtime`) must have `NOBYPASSRLS` and must not own protected tables.
- Migration credentials (`dokene_migration`) are strictly separated from runtime credentials. Tables with `FORCE ROW LEVEL SECURITY` define an explicit migration policy `FOR ALL TO dokene_migration USING (true) WITH CHECK (true)` to permit Flyway schema and data transformations.
- Cross-tenant isolation integration tests against PostgreSQL are mandatory for all tenant-scoped resources.
- `TenantAwareDataSource` rejects transaction-control SQL (`BEGIN`, `COMMIT`, `ROLLBACK`, savepoints, and related commands). Callers must use the corresponding `Connection` APIs so tenant-context lifecycle remains observable and fail-closed.
- JDBC decorators never unwrap to vendor objects: `unwrap` returns only the decorator when it satisfies the requested type, and `isWrapperFor` follows the same rule. A `ResultSet` is bound to the tenant active at creation and rejects access if that context changes or disappears; it may still be closed safely.
- Global tenant-membership discovery uses a separate identity-scoped signed capability and the narrowly granted `dokene.discover_active_tenant_memberships` function. It returns only active memberships of the authenticated identity and is not a public tenant-listing endpoint.
- RLS does not replace authorization checks in application code; both layers are mandatory.
