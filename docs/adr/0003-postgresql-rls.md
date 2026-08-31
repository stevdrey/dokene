# ADR 0003: PostgreSQL Row Level Security

## Status
Accepted

## Decision
Tenant-scoped tables use PostgreSQL Row Level Security (RLS) as a mandatory second isolation boundary in addition to application-level tenant filtering.

## Policy model
Each request or transaction establishes an application-controlled tenant context. The application propagates the trusted server-derived tenant identifier to PostgreSQL via the connection parameter `dokene.current_tenant_id`:
- **Transactional operations**: propagated transaction-locally using `SELECT set_config('dokene.current_tenant_id', ?, true)`. On transaction commit or rollback, the setting expires automatically.
- **Non-transactional (auto-commit) operations**: propagated at session scope using `SELECT set_config('dokene.current_tenant_id', ?, false)`. The session setting is explicitly cleared with `RESET dokene.current_tenant_id` before the connection is returned to the pool. If reset fails, the physical connection is aborted (`Connection.abort()`) to prevent dirty connection reuse.

RLS policies on protected tables restrict visible rows to that tenant and reject writes or reassignments to other tenants.

## Rules
- Protected tenant-scoped tables must enable RLS with `ENABLE ROW LEVEL SECURITY` and `FORCE ROW LEVEL SECURITY` to prevent accidental owner bypass.
- Explicit policies must be defined for `SELECT`, `INSERT`, `UPDATE`, and `DELETE` rather than relying on permissive defaults:
  - `SELECT`: `USING (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid)`
  - `INSERT`: `WITH CHECK (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid)`
  - `UPDATE`: `USING (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid)`
  - `DELETE`: `USING (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid)`
- Missing or empty database tenant context evaluates to `NULL`, failing closed (zero read access and rejection of writes).
- Application runtime role (`dokene_runtime`) must have `NOBYPASSRLS` and must not own protected tables.
- Migration credentials (`dokene_migration`) are strictly separated from runtime credentials. Tables with `FORCE ROW LEVEL SECURITY` define an explicit migration policy `FOR ALL TO dokene_migration USING (true) WITH CHECK (true)` to permit Flyway schema and data transformations.
- Cross-tenant isolation integration tests against PostgreSQL are mandatory for all tenant-scoped resources.
- `TenantAwareDataSource` rejects transaction-control SQL (`BEGIN`, `COMMIT`, `ROLLBACK`, savepoints, and related commands). Callers must use the corresponding `Connection` APIs so tenant-context lifecycle remains observable and fail-closed.
- RLS does not replace authorization checks in application code; both layers are mandatory.
