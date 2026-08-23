# ADR 0002: Shared-Schema Multi-Tenancy

## Status
Accepted

## Decision
Dokene will use a shared PostgreSQL database and shared schema with an explicit `tenant_id` on all tenant-scoped business data.

## Isolation
Tenant identity must come from authenticated server-side context, never from a client-supplied tenant identifier. Tenant isolation is enforced in application queries and reinforced with PostgreSQL Row Level Security.

## Rationale
This model provides an appropriate balance of cost, operational simplicity, and scalability for an early SaaS serving small businesses while preserving a path to dedicated databases for higher-isolation customers later.

## Invariant
No tenant-scoped entity may be accessed without an active tenant context.
