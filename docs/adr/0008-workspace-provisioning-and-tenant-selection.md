# ADR 0008: Controlled Workspace Provisioning and Tenant Selection

## Status

Accepted

## Context

Following OIDC authentication and internal identity establishment ([ADR 0007](0007-oidc-server-side-session.md)), an authenticated operator needs a secure mechanism to provision an initial workspace (tenant) and discover and select among their active workspaces.

Key architectural constraints:
1. **Least Privilege & RLS Integrity**: Tenant-scoped tables enforce PostgreSQL Row-Level Security ([ADR 0003](0003-postgresql-rls.md)). The application runtime role (`dokene_runtime`) must not have `BYPASSRLS` or DDL privileges, nor should the system introduce a general-purpose "privileged" database connection or repository.
2. **Atomicity & Fail-Closed**: Workspace creation requires creating both the `Tenant` entity and an initial `TenantMembership` with role `OWNER`. If either step fails, the entire operation must roll back without leaving orphaned records.
3. **Idempotency**: Network retries or concurrent submissions must not create duplicate workspaces or multiple owners.
4. **Tenant Selection**: Tenant selection must validate active server-side membership and active tenant status. Missing or unauthorized selections must never default to a global or arbitrary tenant.

## Decision

### 1. Atomic Workspace Provisioning Use Case

Dokene provides a dedicated application service (`WorkspaceProvisioningService`) executed within an explicit `@Transactional` boundary.

The service performs:
1. Deterministic validation of inputs (`displayName` within `[1, 160]` characters, valid Unicode scalar sequences without NUL characters; `idempotencyKey` within `[1, 128]` characters).
2. Authorization check against `ProvisioningAuthorizationPolicy`.
3. Idempotency evaluation using `(identity_id, idempotency_key)`:
   - If a matching record exists with the same display name, the existing workspace details are returned (`200 OK`).
   - If a matching record exists with a different display name, the request is rejected with `409 Conflict`.
4. Tenant and owner membership creation:
   - A new `TenantId` is generated.
   - The `Tenant` entity is created with status `ACTIVE` and persisted.
   - The initial `TenantMembership` is created with role `OWNER` and status `ACTIVE`.
   - The membership is persisted within a constrained tenant scope via `tenantContextProvider.callWithTenantId(tenantId, ...)`.
   - A `WorkspaceProvisioningRecord` is persisted in `dokene.workspace_provisioning_records`.
5. Concurrent duplicates caught by the database unique constraint `uq_provisioning_identity_key` safely query the winner's committed workspace.

### 2. Resolution of the Bootstrap / RLS Boundary

PostgreSQL enforces `WITH CHECK (tenant_id = dokene.current_verified_tenant_id())` on `dokene.tenant_memberships`. Before an initial owner exists, no active tenant context exists in session state.

Rather than granting `BYPASSRLS` to `dokene_runtime` or introducing unconstrained superuser queries:
- The provisioning service wraps the membership write in `tenantContextProvider.callWithTenantId(newTenantId, ...)`.
- `TenantAwareDataSource` issues a short-lived (60-second), HMAC-SHA-256-signed tenant capability for `newTenantId` and sets `dokene.tenant_context` and `dokene.tenant_context_signature`.
- PostgreSQL's `dokene.current_verified_tenant_id()` verifies the signature against `dokene.tenant_context_signing_keys` and evaluates to `newTenantId`.
- The RLS check passes strictly for `newTenantId`.
- Once the block exits, the scope unbinds immediately.

### 3. Provisioning Authorization Policy

Workspace provisioning is guarded by `ProvisioningAuthorizationPolicy`:
- `DefaultProvisioningAuthorizationPolicy` evaluates `dokene.provisioning.enabled` (default: `true`) and an optional allowlist `dokene.provisioning.allowed-identities`.
- Unauthorized requests fail closed with `TenantAccessDeniedException` (HTTP 403) and dispatch a durable denial audit event via `AuditRecorder.authorizationDenied`.

### 4. Tenant Discovery & Selection Endpoints

Three authenticated global endpoints are exposed under `/api/tenants`:
1. `GET /api/tenants`:
   - Returns all active workspaces where the caller has an active membership (`tenantMembershipDiscovery.findActiveMemberships(identityId)`).
   - Suspended/archived workspaces and suspended/revoked memberships are excluded server-side.
2. `POST /api/tenants`:
   - Accepts `Idempotency-Key` header (or request body field) and `displayName`.
   - When both key fields are supplied, normalizes them with the canonical idempotency-key rules and rejects a mismatch with `400 Bad Request`.
   - Returns `201 Created` for new workspaces, or `200 OK` for idempotent retries.
3. `GET /api/tenants/{tenantId}`:
   - Validates tenant selection for the authenticated identity via `tenantContextResolver.resolve(identityId, tenantId)`.
   - Unauthorized or foreign tenant requests fail closed with `403 Forbidden` and are audited.

For tenant-scoped business endpoints, the existing `TenantContextRequestFilter` validates `X-Tenant-Id`. If missing, or if resolution fails, it records an audit denial (`NO_TENANT_CONTEXT`) and returns `403 Forbidden`.

## Consequences

- No elevated database permissions (`BYPASSRLS`, DDL, superuser) are granted to `dokene_runtime`.
- Partial failure during provisioning leaves no orphan tenants or memberships.
- Retries and concurrent requests are safely idempotent.
- Cross-tenant isolation remains strictly enforced across application and PostgreSQL RLS layers.
