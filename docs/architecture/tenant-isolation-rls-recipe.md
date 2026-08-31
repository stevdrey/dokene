# Recipe: Adding a New Tenant-Scoped Table with Row-Level Security

## Context & Security Invariants

In Dokene, tenant isolation is enforced at two complementary layers:
1. **Application Layer**: Explicit filtering in domain/repository queries using the trusted `TenantContext`.
2. **Database Layer (RLS)**: PostgreSQL Row-Level Security policies driven by the transaction-local `dokene.current_tenant_id` setting.

Every table containing tenant-scoped business data (such as customers, purchases, templates, messages, etc.) must follow this standardized recipe.

---

## 1. Table Schema Requirements

- Must include `tenant_id UUID NOT NULL`.
- Must declare a foreign key constraint referencing `dokene.tenants(id) ON DELETE RESTRICT`.
- Primary and unique keys should incorporate `tenant_id` where appropriate (e.g. `(tenant_id, normalized_phone_number)` or UUID primary key + unique constraints scoped by `tenant_id`).
- All permissions must be revoked from `PUBLIC`, and DML permissions granted to `dokene_runtime`.

### Example DDL:

```sql
CREATE TABLE dokene.customers (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    phone_number VARCHAR(32) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_customers_tenant
        FOREIGN KEY (tenant_id) REFERENCES dokene.tenants (id) ON DELETE RESTRICT,
    CONSTRAINT uq_customers_tenant_phone UNIQUE (tenant_id, phone_number),
    CONSTRAINT ck_customers_updated_at_not_before_created_at CHECK (updated_at >= created_at)
);

REVOKE ALL ON TABLE dokene.customers FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE dokene.customers TO dokene_runtime;
```

---

## 2. Row-Level Security Policy Requirements

Every tenant-scoped table must:
1. Enable RLS: `ALTER TABLE ... ENABLE ROW LEVEL SECURITY;`
2. Force RLS for table owner: `ALTER TABLE ... FORCE ROW LEVEL SECURITY;`
3. Define explicit CRUD policies targeting `dokene_runtime`:

```sql
ALTER TABLE dokene.customers ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.customers FORCE ROW LEVEL SECURITY;

CREATE POLICY customers_select_policy
    ON dokene.customers
    FOR SELECT
    TO dokene_runtime
    USING (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid);

CREATE POLICY customers_insert_policy
    ON dokene.customers
    FOR INSERT
    TO dokene_runtime
    WITH CHECK (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid);

CREATE POLICY customers_update_policy
    ON dokene.customers
    FOR UPDATE
    TO dokene_runtime
    USING (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid);

CREATE POLICY customers_delete_policy
    ON dokene.customers
    FOR DELETE
    TO dokene_runtime
    USING (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid);

CREATE POLICY customers_migration_policy
    ON dokene.customers
    FOR ALL
    TO dokene_migration
    USING (true)
    WITH CHECK (true);
```

---

## 3. Database Session Context Mechanism

- Application requests establish a `TenantContext` using `ScopedValueTenantContextProvider`.
- Database connections automatically propagate tenant context via `TenantAwareDataSource`:
  - **In transactions**: executes `SELECT set_config('dokene.current_tenant_id', ?, true)` deferred until statement preparation. The setting expires automatically upon `COMMIT` or `ROLLBACK`.
  - **In auto-commit mode**: executes `SELECT set_config('dokene.current_tenant_id', ?, false)` and executes `RESET dokene.current_tenant_id` on connection close before returning to HikariCP.
  - **Connection eviction**: if session reset fails, `Connection.abort()` is called to destroy the physical connection and eliminate pool contamination.
  - **Transaction control**: raw SQL transaction commands are rejected. Use `Connection.setAutoCommit`, `commit`, `rollback`, and savepoint APIs so the decorator can maintain the RLS context lifecycle.
- In PostgreSQL, `NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid` evaluates to `NULL` when context is absent, causing queries to fail closed (0 rows returned for reads, RLS violation on writes).

---

## 4. Testing Checklist for New Tenant-Scoped Tables

When adding a new tenant-scoped table:
1. Verify `Flyway` migration runs cleanly with `dokene_migration` role.
2. Verify `relrowsecurity = true` and `relforcerowsecurity = true` in `pg_class`.
3. Verify that `SELECT`, `INSERT`, `UPDATE`, and `DELETE` policies exist in `pg_policies`.
4. Include Testcontainers integration tests verifying:
   - Queries within Tenant A context return only Tenant A rows (even if query filter is omitted).
   - Attempts to insert or reassign rows to Tenant B while in Tenant A context fail with an RLS policy violation.
   - Operations executed without an active tenant context fail closed.
