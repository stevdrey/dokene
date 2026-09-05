# ADR 0005: Tenant-Aware Authorization and Permission Evaluation

## Status
Accepted

## Decision
Dokene implements an explicit, tenant-aware authorization layer that evaluates identity, tenant scope, membership state, roles, and granular permissions before application use cases execute. Authorization fails closed by default and operates independently of any concrete identity provider.

## Permission Model & Vocabulary
Authorization decisions are governed by a central, strongly typed vocabulary of permissions (`TenantPermission`) rather than ad-hoc role comparisons across controllers and services:

- **Tenant Lifecycle**: `TENANT_READ`, `TENANT_UPDATE`, `TENANT_ARCHIVE`
- **Membership Management**: `MEMBERSHIP_READ`, `MEMBERSHIP_INVITE`, `MEMBERSHIP_ROLE_UPDATE`, `MEMBERSHIP_REVOKE`
- **Customer Data**: `CUSTOMER_READ`, `CUSTOMER_WRITE`, `CUSTOMER_DELETE`
- **Follow-up Operations**: `FOLLOWUP_READ`, `FOLLOWUP_WRITE`, `FOLLOWUP_EVALUATE`
- **Template Management**: `TEMPLATE_READ`, `TEMPLATE_WRITE`
- **Messaging & Approval**: `MESSAGE_READ`, `MESSAGE_DRAFT`, `MESSAGE_APPROVE`, `MESSAGE_SEND`
- **Integrations**: `INTEGRATION_READ`, `INTEGRATION_MANAGE`
- **Audit & Export**: `AUDIT_READ`, `DATA_EXPORT`

## Role-to-Permission Mapping
Tenant roles (`TenantRole`) map deterministically and immutably to sets of permissions (`TenantRolePermissions`):

- `OWNER`: Full tenant permissions, including workspace archival (`TENANT_ARCHIVE`).
- `ADMIN`: Comprehensive administration and operational capabilities, excluding `TENANT_ARCHIVE`.
- `OPERATOR`: Operational permissions (customer read/write, drafts, templates, follow-up evaluation, message send/approve), without administrative tenant or membership modification capabilities.
- `VIEWER`: Read-only access to tenant information, memberships, customers, follow-ups, templates, and messages.

## Invariants & Rules
1. **Fail-Closed by Default**: Any authorization check without an active `TenantContext`, with an inactive/suspended/revoked membership, or with an unmapped permission fails immediately with denial.
2. **Resource-Level IDOR Prevention**: Resource access checks (`TenantScopedResource`) verify that the target entity's `tenantId` strictly equals the active `TenantContext.tenantId()`. Client-provided identifiers never constitute proof of ownership.
3. **Provider-Neutral Domain Abstraction**: Core authorization (`TenantAuthorizationService`, `AuthorizationDecision`, `AuthorizationDeniedEvent`) is decoupled from Spring Security and identity providers.
4. **Adapter Integration**: Spring Security integration is provided through `TenantPermissionEvaluator`, `@EnableMethodSecurity`, and `@tenantAuth` SpEL expressions (`@PreAuthorize("@tenantAuth.hasPermission('CUSTOMER_READ')")`).
5. **Auditing without Information Leakage**: Denied authorization evaluations trigger notifications to `AuthorizationAuditListener` capturing complete security context (actor, tenant, membership, required permission, failure reason) while returning a generic `TenantAccessDeniedException` (HTTP 403) to clients to prevent reconnaissance.

## Durable audit integration

[ADR 0006](0006-durable-append-only-audit.md) replaces the production no-op listener
with synchronous durable denials. Audit failures propagate and produce generic HTTP
503 responses rather than being swallowed before returning 403. Pure `evaluate`
methods remain side-effect-free; enforcement methods (`require*`, `has*`) audit denials.
