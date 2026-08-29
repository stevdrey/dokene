# Multi Tenancy and Data

## Tenant model

Dokene is designed as a multi-tenant SaaS from the beginning.

A tenant represents a business/customer account. Users are separate identities that can belong to one or more tenants through memberships and roles.

Do not model `user == tenant`.

Conceptually:

```text
User
  │
  └── Membership ──> Tenant
                         │
                         ├── Customers
                         ├── Purchases
                         ├── Follow-ups
                         ├── Templates
                         ├── Integrations
                         └── Messages
```

## Initial persistence strategy

The MVP uses:

- one PostgreSQL database;
- one shared application schema;
- `tenant_id` on tenant-owned tables;
- application-level tenant filters;
- PostgreSQL Row Level Security (RLS) as defense in depth.

This offers a strong balance between operational simplicity and isolation for an early SaaS.

## Why not schema-per-tenant initially?

Schema-per-tenant offers stronger logical separation, but introduces migration, connection-management, tooling, and operational complexity early.

## Why not database-per-tenant initially?

Database-per-tenant offers strong isolation and can be appropriate for enterprise customers, but it increases provisioning, cost, migration, backup, monitoring, and operational complexity.

A future hybrid model is possible:

```text
SMB tenants       → shared database/schema
Enterprise tenant → dedicated database when justified
```

## Tenant context

The active tenant must be derived from authenticated membership/session state.

Never treat a request field such as:

```text
tenantId=<uuid>
```

as trusted proof that the caller belongs to that tenant.

A request may select among memberships the user already has, but authorization must resolve that selection against trusted membership state.

## PostgreSQL RLS

Tenant-owned tables enforce database-level isolation using PostgreSQL Row-Level Security (RLS).

Every tenant-scoped table enables and forces RLS with explicit CRUD policies:

```sql
ALTER TABLE dokene.customer ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.customer FORCE ROW LEVEL SECURITY;

CREATE POLICY customer_select_policy
ON dokene.customer FOR SELECT TO dokene_runtime
USING (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid);

CREATE POLICY customer_insert_policy
ON dokene.customer FOR INSERT TO dokene_runtime
WITH CHECK (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid);

CREATE POLICY customer_update_policy
ON dokene.customer FOR UPDATE TO dokene_runtime
USING (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid)
WITH CHECK (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid);

CREATE POLICY customer_delete_policy
ON dokene.customer FOR DELETE TO dokene_runtime
USING (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid);
```

The application sets tenant context transaction-locally on the database connection:

```sql
SELECT set_config('dokene.current_tenant_id', '<tenant-uuid>', true);
```

When database tenant context is absent, policies evaluate to `NULL` and fail closed.

See `docs/architecture/tenant-isolation-rls-recipe.md` and `docs/adr/0003-postgresql-rls.md`.

## Data ownership

Examples of tenant-scoped entities:

- customer;
- purchase;
- follow-up;
- message;
- message template;
- integration configuration;
- tenant settings;
- audit records associated with tenant actions.

Global/platform-owned tables must be explicitly identified rather than accidentally omitting `tenant_id`.

## Customer identity

A useful initial uniqueness rule may be:

```text
(tenant_id, normalized_phone_number)
```

rather than globally unique phone number.

A customer can legitimately exist in multiple businesses/tenants.

## Message templates

Tenant-authored message templates belong in PostgreSQL as versioned business data.

Do not make runtime filesystem `.txt` files the source of truth for tenant-specific templates in a horizontally deployed SaaS.

A template record should be capable of representing:

- tenant;
- name;
- channel;
- category;
- language;
- content/structured definition;
- status;
- provider template identifier where applicable;
- version;
- timestamps.

Repository resource files may define system defaults that are copied/provisioned into tenant data.

## Message immutability and provenance

Once a message is approved/sent, keep enough immutable provenance to reconstruct what was actually sent.

Useful data includes:

- rendered content;
- template identifier/version;
- recommendation/action source;
- approval actor/time;
- provider message identifier;
- send/delivery/failure timestamps;
- safe failure reason/status.

Do not rely on the current template text to reconstruct historical messages after a template changes.

## Data minimization

Only collect data needed for product operation.

Classify data at least conceptually as:

```text
PUBLIC
INTERNAL
CONFIDENTIAL
SECRET
```

Examples:

- public documentation → `PUBLIC`;
- internal business configuration → `INTERNAL`/`CONFIDENTIAL`;
- customer contact/purchase data → `CONFIDENTIAL`;
- API keys/tokens → `SECRET`.

## Exports and deletion

Data export is a sensitive operation and should require explicit permission, audit logging, and rate limiting; re-authentication may be appropriate for high-risk exports.

Tenant deletion should have a defined lifecycle covering:

- application rows;
- integration secrets/references;
- queued work;
- exports;
- backups/retention obligations;
- audit requirements.

## Backups

Backups should be encrypted, access-controlled, retention-aware, and periodically restore-tested. A backup that has never been restored successfully is not a proven recovery strategy.
