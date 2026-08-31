# Recipe: Adding a New Tenant-Scoped Table with Row-Level Security

## Context & Security Invariants

In Dokene, tenant isolation is enforced at two complementary layers:
1. **Application Layer**: Explicit filtering in domain/repository queries using the trusted `TenantContext`.
2. **Database Layer (RLS)**: PostgreSQL Row-Level Security policies driven by a short-lived, signed tenant capability verified inside PostgreSQL.

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
    USING (tenant_id = dokene.current_verified_tenant_id());

CREATE POLICY customers_insert_policy
    ON dokene.customers
    FOR INSERT
    TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());

CREATE POLICY customers_update_policy
    ON dokene.customers
    FOR UPDATE
    TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());

CREATE POLICY customers_delete_policy
    ON dokene.customers
    FOR DELETE
    TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());

CREATE POLICY customers_migration_policy
    ON dokene.customers
    FOR ALL
    TO dokene_migration
    USING (true)
    WITH CHECK (true);
```

---

## 3. Database Session Context Mechanism

- Application requests establish a `TenantContext` using `ScopedValueTenantContextProvider` only after membership bootstrap authorizes the requested tenant.
- `DOKENE_TENANT_CONTEXT_SIGNING_KEY` is exactly 32 random bytes encoded as 64 hexadecimal characters. `DOKENE_TENANT_CONTEXT_KEY_ID` (defaulting to `default`) identifies the active key. Keys are provisioned into `dokene.tenant_context_signing_keys` via parameterized JDBC callbacks during migration, avoiding plaintext SQL literals.
- `dokene.tenant_context_signing_keys` stores keys by `key_id` with status `ACTIVE` or `RETIRED`. `SECURITY DEFINER` functions in schema `dokene` use a fixed `search_path` and verify the canonical `tenant|<key_id>|<uuid>|<expiry>|<nonce>` payload and HMAC-SHA-256 signature against the active key for that `key_id`, valid for 60 seconds. Every invalid form returns `NULL`.
- Database connections automatically propagate the signed tenant capability via `TenantAwareDataSource`:
  - **In transactions**: both `dokene.tenant_context` and `dokene.tenant_context_signature` are set transaction-locally with `set_config(..., true)` just before JDBC work. The settings expire automatically upon `COMMIT` or `ROLLBACK`. Tenant-scoped database work should execute inside explicit transactions.
  - **In auto-commit mode**: both settings are set at session scope with `set_config(..., false)` and are reset on connection close before returning to HikariCP.
  - **Connection eviction**: any context propagation or cleanup failure, including closure of the propagation statement, calls `Connection.abort()` to destroy the physical connection and eliminate pool contamination.
  - **Transaction control**: raw SQL transaction commands are rejected. Use `Connection.setAutoCommit`, `commit`, `rollback`, and savepoint APIs so the decorator can maintain the RLS context lifecycle.
  - **JDBC boundaries**: `unwrap` never exposes a vendor connection, statement, metadata object, or result set. A result set remains usable only while the tenant that created it is current; closing it remains safe after scope exit.
- The capability parameters are not authority: manually changing a custom PostgreSQL setting cannot select another tenant without a valid matching signature. `dokene.current_verified_tenant_id()` returns `NULL` otherwise, so reads return no rows and writes receive an RLS violation.

### Key Rotation Lifecycle

1. Provision the next signing key with `key_id = k2` and `status = 'ACTIVE'` in `dokene.tenant_context_signing_keys`.
2. Deploy the application with `DOKENE_TENANT_CONTEXT_KEY_ID=k2` and `DOKENE_TENANT_CONTEXT_SIGNING_KEY=<key_2_hex>`.
3. In-flight capabilities signed with `k1` continue verifying during the 60-second capability validity window.
4. After the expiration window, mark `k1` as `RETIRED` (`status = 'RETIRED'`).

### Global membership bootstrap

`TenantMembershipDiscovery` is an internal port used before the requested tenant is bound. Its PostgreSQL adapter supplies a separate identity-scoped signed capability (`identity|<key_id>|<uuid>|<expiry>|<nonce>`) to `dokene.discover_active_tenant_memberships`, which returns only active memberships for that identity and active tenants. No HTTP tenant-listing endpoint is created by this mechanism.

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
