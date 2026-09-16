# ADR 0014: Frontend Authenticated Shell and Workspace Selection

## Status

Accepted

## Context

Following server-side OIDC authentication ([ADR 0007](0007-oidc-server-side-session.md)) and controlled workspace provisioning ([ADR 0008](0008-workspace-provisioning-and-tenant-selection.md)), Dokene requires an authenticated frontend application shell that:
1. Coordinates browser sessions with Spring Security's session and CSRF contracts without persisting tokens in browser storage (`localStorage` or `sessionStorage`).
2. Enforces tenant context boundaries by attaching `X-Tenant-Id` only to authorized, tenant-scoped API requests.
3. Completely prevents cross-tenant data bleed and in-flight race conditions during workspace switching.
4. Manages unauthenticated, expired-session (401), forbidden (403), and no-membership states cleanly and accessibly.
5. Implements the visual hierarchy and responsive composition reconciled from the owner-supplied Stitch design export (`stitch_dokene_customer_follow_up_ui`).
6. Enforces frontend supply chain security protections against malicious package lifecycle execution and unexpected version drift.

## Decision

### 1. Session Lifecycle & Transport Security

- The frontend interacts with `/api/session` (`GET`) on boot to determine authentication status and retrieve the active `identityId` and `csrfToken`.
- All credentials remain in the `HttpOnly`, `Secure`, `SameSite=Lax` cookie managed by the browser; provider tokens are never exposed to or stored by client scripts.
- Unsafe HTTP methods (`POST`, `PUT`, `DELETE`, `PATCH`) automatically receive the `X-CSRF-TOKEN` header.
- On logout (`POST /logout`), the CSRF token is sent, cookies are cleared by the backend, all in-flight requests are aborted, and local client state is reset to `unauthenticated`.
- An expired session (401 received on any authenticated endpoint) immediately invalidates client tenant state and returns the operator to the login view with a clear, accessible Latin American Spanish notification.

### 2. Tenant Context & Race-Condition Isolation

- Workspaces are discovered dynamically from `GET /api/tenants`. Frontend visibility is never treated as authorization: the backend verifies active membership on each request.
- The active tenant is tracked in `TenantContext`. Tenant-scoped requests attach `X-Tenant-Id: <uuid>`.
- **Cancellation & Late-Response Guard**:
  - Each tenant-scoped request is registered with an internal `AbortController` scoped to the current `tenantId`.
  - When switching workspaces, `cancelTenantRequests(previousTenantId)` immediately aborts all pending network calls for the previous tenant.
  - Any late response whose target tenant ID does not match the currently active tenant is discarded immediately (`AbortedTenantRequestError`), guaranteeing that responses from Tenant A cannot be rendered in Tenant B's view.
- When an account has zero memberships, the user is transitioned to the dedicated `NoMembershipsView` where an initial workspace can be provisioned via `POST /api/tenants` with an `Idempotency-Key`.

### 3. Visual Tokens & Design Reconciliations

The UI implements the reconciled semantic token layer specified in Issue #37:
- **Brand & Emphasis**: `#005C55`
- **Primary CTA**: `#0F766E` (hover: `#115E59`, text: `#FFFFFF`)
- **Selected Surface**: `#E6F4F1`
- **Canvas / Background**: Desktop canvas `#FFFFFF`, mobile canvas `#E7FFF6`, neutral insets `#F7F8FA`
- **Text & Outlines**: Text `#0B1F1A`, muted text `#3E4947`, outline `#BDC9C6`
- **Feedback States**: Warning bg `#FEF3C7`, text `#92400E`; Error bg `#FEF3F2`, text `#B42318`
- **Layout & Responsiveness**:
  - Desktop: 232px fixed sidebar with Dokene brand, workspace selector, "Seguimientos", "Clientes", "Configuración", user profile initials and sign-out button.
  - Mobile (< 768px): Header with compact "D" mark, workspace switcher and profile; fixed bottom navigation with "Seguimientos", "Clientes", "Más" and safe-area padding.
  - Interactive targets enforce a minimum 44x44px boundary and 2px visible focus rings (`:focus-visible`).
- **Language**: All user-facing UI copy is Latin American Spanish; code, comments, and PR documentation remain in English.

### 4. Supply Chain Attack Prevention

- Strict `.npmrc` configuration (`ignore-scripts=true`, `save-exact=true`, `package-lock=true`, `audit=true`, `audit-level=high`, `registry=https://registry.npmjs.org/`).
- Elimination of dynamic `"latest"` tags and loose range operators (`^`, `~`) in `package.json`.
- Committed `package-lock.json` with cryptographic SHA-512 hashes for all direct and transitive dependencies.
- CI workflows use `npm ci --ignore-scripts` and execute automated security audits (`npm run audit`).

## Consequences

- Tenant-scoped data is isolated in browser memory and cannot leak across workspace switches or expired sessions.
- Client state transitions are predictable, testable, and resilient to network latency differences.
- The visual presentation strictly matches approved design assets while maintaining accessibility standards.
- Build and installation workflows are hardened against common npm supply chain attack vectors.
