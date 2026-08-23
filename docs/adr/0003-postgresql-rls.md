# ADR 0003: PostgreSQL Row Level Security

## Status
Accepted

## Decision
Tenant-scoped tables will use PostgreSQL Row Level Security as defense in depth in addition to application-level tenant filtering.

## Policy model
Each request or transaction establishes an application-controlled tenant context. RLS policies restrict visible rows to that tenant.

## Rules
- Application runtime roles must not bypass RLS.
- Migration credentials are separate from runtime credentials.
- Cross-tenant isolation tests are mandatory for tenant-scoped resources.
- RLS does not replace authorization checks in application code.
