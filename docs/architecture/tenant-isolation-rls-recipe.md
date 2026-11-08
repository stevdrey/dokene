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
- Contact identity constraints must retain `tenant_id` through child-table foreign keys. Customer phone uniqueness
  applies to active and archived profiles so archival cannot silently reassign identity.
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

A key verifies while its row is `ACTIVE` and its `retire_after` is unset or still in the future on the database clock. `dokene.verification_signing_key(key_id)` (V39) is the only reader of the key table; every verifier (`signed_context_subject`, `append_audit_event`) goes through it. The migration job (`TenantContextSigningKeyCallback`) runs, in one transaction serialized by a table lock:

- refuse to start if the configured `key_id` is `RETIRED` or past its `retire_after`: a retired key is never reactivated;
- refuse to start if the configured `key_id` already holds different key material: a key id never changes its key, because that would revoke the old material at once, with no grace;
- upsert the configured key as `ACTIVE` with no `retire_after`;
- give every other `ACTIVE` key `retire_after = now() + DOKENE_TENANT_CONTEXT_KEY_RETIREMENT_GRACE`, or keep its earlier deadline: re-running the job never extends a grace, and a shorter grace shortens it;
- mark keys past their deadline `RETIRED`, with `retired_at = retire_after`, and overwrite their key material with random bytes.

The grace (default `1h`, range `0s` to `7d`) must cover the longest rolling deployment plus the 60-second capability lifetime and the 70-second skew the verifiers accept. The deadline is enforced by the verifiers on the database clock, so it holds across instances and even if the job never runs again; the `RETIRED` status is only recorded at the next job run.

#### Runbook: planned rotation

1. Generate the next key (`openssl rand -hex 32`) and choose a new key id, for example `k2`. Never reuse a key id.
2. Store the new key in the secret store, set `DOKENE_TENANT_CONTEXT_KEY_ID=k2`, and set `DOKENE_TENANT_CONTEXT_KEY_RETIREMENT_GRACE` if the default does not cover the rollout.
3. Run the migration job (in Compose, `docker compose up` runs `migrate` before the backend). It provisions `k2` and schedules `k1` for retirement.
4. Roll the backend instances. Instances still signing with `k1` and new ones signing with `k2` both verify until the grace ends.
5. Finish the rollout before the grace ends. An instance still signing with `k1` after that fails closed: every tenant-scoped read returns no rows and writes are refused.
6. Optionally re-run the job after the grace to record `k1` as `RETIRED`. Check with the migration role: `SELECT key_id, status, retire_after, retired_at FROM dokene.tenant_context_signing_keys` (never select `signing_key`).

#### Runbook: rollback

- **Within the grace:** redeploy with the previous key id and key, and run the migration job first. It reactivates `k1` (clears its deadline) and schedules `k2` instead.
- **After the grace:** the job refuses the retired key id and exits non-zero without changing any key. Roll forward with a new key id (`k3`) instead; the application rollback itself does not depend on the key id.
- **Rolling the release back to before V39:** keep the current key id. The job of that release knows nothing about retirement deadlines: it would leave a reactivated key's `retire_after` in place, so a key it "reactivates" still stops verifying at its old deadline (or at once, if it was already retired). Key rotation and rollback need the V39 job or later.

#### Runbook: emergency revocation of a leaked key

Rotate as above with `DOKENE_TENANT_CONTEXT_KEY_RETIREMENT_GRACE=0s`: the leaked key stops verifying as soon as the job commits, and instances still signing with it fail closed until they are replaced. If the job has already run with a longer grace, re-run it with `0s`; the earlier deadline is shortened, never extended.

### Global membership bootstrap

`TenantMembershipDiscovery` is an internal port used before the requested tenant is bound. Its PostgreSQL adapter supplies a separate identity-scoped signed capability (`identity|<key_id>|<uuid>|<expiry>|<nonce>`) to `dokene.discover_active_tenant_memberships`, which returns only active memberships for that identity and active tenants. No HTTP tenant-listing endpoint is created by this mechanism.

### Tenant root and provisioning bootstrap

`dokene.tenants` is itself RLS-protected, keyed on `id = dokene.current_verified_tenant_id()`: runtime code reads or updates a tenant row only inside that tenant's scope, never deletes it, and may update only `display_name`, `status`, `updated_at`, and `version`. Workspace provisioning creates the tenant, owner membership, and `workspace_provisioning_records` row inside `callWithTenantId(newTenantId, ...)`, writes the record through `dokene.record_workspace_provisioning` (identity capability plus the new tenant's context), and resolves idempotent retries through the identity-scoped `dokene.find_workspace_provisioning_record` function; the runtime role has no direct privilege on that table. Tests that seed tenants must do so inside the new tenant's scope.

---

## 4. Testing Checklist for New Tenant-Scoped Tables

When adding a new tenant-scoped table:
1. Verify `Flyway` migration runs cleanly with `dokene_migration` role.
2. Verify `relrowsecurity = true` and `relforcerowsecurity = true` in `pg_class`.
3. Verify that `SELECT`, `INSERT`, `UPDATE`, and `DELETE` policies exist in `pg_policies`. (not for the tenant root or `workspace_provisioning_records`, whose missing policies are deliberate; see [ADR 0003](../adr/0003-postgresql-rls.md#tenant-root-and-provisioning-records)).
4. Include Testcontainers integration tests verifying:
   - Queries within Tenant A context return only Tenant A rows (even if query filter is omitted).
   - Attempts to insert or reassign rows to Tenant B while in Tenant A context fail with an RLS policy violation.
   - Operations executed without an active tenant context fail closed.
