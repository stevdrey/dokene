# Issue 52 Verification: End-to-End BFF Login, Session Lifecycle, CSRF, and Tenant Boundary Enforcement

Date: 2026-09-13

## Overview

This document records the comprehensive verification for [Issue #52](https://github.com/stevdrey/dokene/issues/52):
- Proving the complete Keycloak -> Spring Boot BFF -> browser application session flow.
- Proving that OAuth tokens (`access_token`, `id_token`, `refresh_token`) and the OIDC client secret are strictly isolated server-side and never exposed to the browser.
- Proving session cookie security attributes (`HttpOnly`, `SameSite=Lax`).
- Proving session fixation protection end-to-end.
- Proving CSRF protection on representative state-changing mutations.
- Proving fail-closed `401 Unauthorized` vs `403 Forbidden` behavior across endpoints.
- Proving multi-tenant boundary isolation and workspace switching under real PostgreSQL Row Level Security (RLS) after real authentication.
- Proving session invalidation on logout and clean recovery from expired sessions.

## Environment

- Java Baseline: Temurin 26.0.2.1
- Spring Boot: 4.1.1 (Modular Monolith)
- Database: PostgreSQL 17 (`postgres:17-alpine`) with Flyway migrations V1 through V11
- OIDC Identity Provider: Keycloak 26.7.3 (`quay.io/keycloak/keycloak:26.7.3`) with imported `dokene` realm
- Test User: `testuser` (`testuser@dokene.local`) with synthetic local password
- Test Client: `dokene-bff` confidential OIDC client (RFC 7636 PKCE S256, Authorization Code flow)
- Frontend Test Environment: Vitest 3.2.7 with React Testing Library

All credentials, tokens, session IDs, and tenant/customer UUIDs are synthetic and local-only.

## How to Run Verification

### 1. Automated Integration Suite (Backend JVM)

Runs self-contained integration tests with Testcontainers PostgreSQL and real RLS policies:

```bash
# Run all BFF security, session lifecycle, and tenant boundary tests
(cd backend && ./gradlew test --tests "io.github.stevdrey.dokene.identity.security.*")

# Run full project checks
(cd backend && ./gradlew check)
```

### 2. Frontend State & Workspace Verification

Runs the React/TypeScript test suite verifying session context, workspace switching races, and session storage hygiene:

```bash
(cd frontend && npm test -- --run)
```

### 3. Repeatable End-to-End Keycloak BFF Verification Script

Executes the automated black-box cURL sequence against the reproducible local Keycloak container from Issue #50 and the Spring Boot BFF from Issue #51:

```bash
# Step A: Start local PostgreSQL and Keycloak containers
docker compose up -d

# Step B: Launch Spring Boot backend
set -a; [ -f .env ] && . ./.env; set +a
(cd backend && ./gradlew bootRun)

# Step C: In another terminal, run the verification script
./scripts/verify-keycloak-bff-e2e.sh
```

---

## Verified Scenarios & Test Matrix

### 1. Keycloak OIDC Authentication & BFF Session Establishment

| Scenario | Request / Flow | Expected | Observed | Status |
| --- | --- | --- | --- | --- |
| Anonymous session status | `GET /api/session` | `401 Unauthorized` | HTTP 401 fail-closed, no session created | PASS |
| Login initiation | `GET /oauth2/authorization/dokene` | `302 Found` with redirect to Keycloak auth endpoint and pre-auth `JSESSIONID` | HTTP 302 redirecting to Keycloak with PKCE S256 parameters (`code_challenge`) and state | PASS |
| Keycloak credentials submit | `POST /realms/dokene/login-actions/authenticate` with `testuser` credentials | `302 Found` redirect to `/login/oauth2/code/dokene` with single-use code | HTTP 302 redirecting to Dokene BFF callback with authorization code | PASS |
| Code exchange & session creation | `GET /login/oauth2/code/dokene?code=...&state=...` | BFF performs confidential code exchange server-side, rotates session ID, redirects to `/` | HTTP 302 redirecting to `/`; session cookie rotated | PASS |
| Session contract inspection | `GET /api/session` with rotated `JSESSIONID` | `200 OK` with `{ "authenticated": true, "identityId": "...", "csrfToken": "..." }` | HTTP 200 with minimal application session metadata | PASS |
| Token isolation | Inspection of callback redirect, session response body, headers, and logs | Strictly ZERO occurrence of `access_token`, `id_token`, `refresh_token`, or client secret | No tokens or secrets present in any client-accessible artifact | PASS |
| Cookie security attributes | Inspection of `Set-Cookie: JSESSIONID=...` | Must declare `HttpOnly` and `SameSite=Lax` (and `Secure` in TLS environments) | `HttpOnly; SameSite=Lax` present on session cookies | PASS |

### 2. Session Fixation & Session Lifecycle

| Scenario | Request / Flow | Expected | Observed | Status |
| --- | --- | --- | --- | --- |
| Pre-authentication session adoption | Replaying pre-login `JSESSIONID` to `GET /api/session` after successful login | `401 Unauthorized` | Pre-auth session invalidated upon code exchange; pre-auth cookie cannot adopt login | PASS |
| Tampered session cookie | `GET /api/session` with `Cookie: JSESSIONID=forged-cookie-12345` | `401 Unauthorized` | Fail-closed HTTP 401 without creating session or redirect loop | PASS |
| Expired session fail-closed | Requesting `/api/session` after session expiration | `401 Unauthorized` | Inactive session returns HTTP 401 | PASS |
| Expired session recovery | Navigating to `/oauth2/authorization/dokene` after expiration | Successful new login establishing fresh session | Authenticates cleanly and yields new authorized `JSESSIONID` | PASS |
| Local logout with CSRF | `POST /logout` with `X-CSRF-TOKEN` | `204 No Content`, session invalidated, cookie deleted | HTTP 204 returned; session cleared server-side | PASS |
| Post-logout session reuse | Replaying logged-out `JSESSIONID` to `/api/session` or `/api/customers` | `401 Unauthorized` | HTTP 401 fail-closed; session ID cannot be revived | PASS |
| Provider SSO logout | `POST /logout?provider=true` with `X-CSRF-TOKEN` | `302 Found` redirect to Keycloak end_session endpoint with `id_token_hint` | HTTP 302 redirect to Keycloak logout; local session invalidated | PASS |

### 3. CSRF Protection on State-Changing Endpoints

| Scenario | Request | Expected | Observed | Status |
| --- | --- | --- | --- | --- |
| Logout without CSRF | `POST /logout` without `X-CSRF-TOKEN` | `403 Forbidden` | HTTP 403 (CSRF filter rejects before action) | PASS |
| Logout with forged CSRF | `POST /logout` with `X-CSRF-TOKEN: forged-token` | `403 Forbidden` | HTTP 403 | PASS |
| Customer mutation without CSRF | Authenticated `POST /api/customers` without `X-CSRF-TOKEN` | `403 Forbidden` | HTTP 403 | PASS |
| Customer mutation with forged CSRF | Authenticated `POST /api/customers` with `X-CSRF-TOKEN: invalid` | `403 Forbidden` | HTTP 403 | PASS |
| Customer mutation with valid CSRF | Authenticated `POST /api/customers` with valid `X-CSRF-TOKEN` | `201 Created` | HTTP 201 Created | PASS |
| Workspace provisioning without CSRF | Authenticated `POST /api/tenants` without `X-CSRF-TOKEN` | `403 Forbidden` | HTTP 403 | PASS |
| Workspace provisioning with valid CSRF | Authenticated `POST /api/tenants` with valid `X-CSRF-TOKEN` and `Idempotency-Key` | `201 Created` | HTTP 201 Created with provisioned tenant metadata | PASS |

### 4. Tenant Membership & Cross-Tenant Boundary Isolation

| Scenario | Request / Flow | Expected | Observed | Status |
| --- | --- | --- | --- | --- |
| Authenticated identity without membership | Authenticated user (Charlie) calls `GET /api/tenants` | `200 OK` with empty list `[]` | Empty array returned | PASS |
| Authenticated identity without membership accessing tenant API | Authenticated Charlie calls `GET /api/customers` with `X-Tenant-Id: <random-uuid>` | `403 Forbidden` | HTTP 403 fail-closed (no membership in nominated tenant) | PASS |
| Cross-tenant customer isolation | User B (Tenant B) accesses User A's customer (`GET /api/customers/{idA}`) with `X-Tenant-Id: {tenantB}` | `404 Not Found` | Row Level Security ensures query yields empty result | PASS |
| Forged tenant selector | User B accesses User A's customer with forged `X-Tenant-Id: {tenantA}` | `403 Forbidden` | Membership verification rejects foreign tenant selector | PASS |
| Forged tenant list query | User B calls `GET /api/customers` with forged `X-Tenant-Id: {tenantA}` | `403 Forbidden` | Membership verification rejects selector | PASS |
| Forged tenant mutation | User B calls `POST /api/customers` with forged `X-Tenant-Id: {tenantA}` | `403 Forbidden` | Membership verification rejects mutation | PASS |
| Session adoption prevention | User B attempts to attach User A's `JSESSIONID` to impersonate User A | Bound to User A | Session retains Identity A and cannot adopt User B | PASS |
| Workspace switching in same session | User with memberships in Tenant A and Tenant B sends sequential requests toggling `X-Tenant-Id` | Strict isolation per request | Request for Tenant A returns only Tenant A items; request for Tenant B returns only Tenant B items; no cached/stale data leaks | PASS |
| Insufficient role permissions | User with `VIEWER` role calls `POST /api/customers` with valid CSRF | `403 Forbidden` | `CUSTOMER_WRITE` required; VIEWER rejected with HTTP 403 | PASS |
| Frontend late-response cancellation | User switches workspace while request for previous tenant is in flight | Abort prior request | Client rejects old request with `AbortedTenantRequestError`; active workspace state is never contaminated | PASS |
| Frontend identity storage cleanup | User logs out of authenticated shell | Clean storage | Identity-scoped sessionStorage keys are removed immediately | PASS |

---

## Acceptance Criteria Summary

- [x] **Automated & deterministic test for Keycloak -> Spring Boot BFF -> session login flow**: Covered by both `scripts/verify-keycloak-bff-e2e.sh` and `io.github.stevdrey.dokene.identity.security.*` test suite.
- [x] **OAuth access, refresh, and ID tokens are not exposed to frontend contracts**: Verified on `/api/session`, callback redirect URLs, error responses, and application logs.
- [x] **Session fixation protection verified end-to-end**: Pre-authentication session is rotated and invalidated; reuse yields `401 Unauthorized`.
- [x] **CSRF rejection and success paths covered on representative mutations**: Verified on `POST /logout`, `POST /api/customers`, and `POST /api/tenants`.
- [x] **Logout and expired-session behavior verified**: Local and provider logout invalidate sessions; post-logout reuse fails closed; expired sessions recover cleanly.
- [x] **401 vs 403 behavior covered**: Unauthenticated requests return `401`; authenticated requests lacking membership or permission return `403`.
- [x] **Tenant membership and cross-tenant isolation enforced after real authentication**: Verified across two distinct identities, two tenants, and PostgreSQL RLS.
- [x] **Workspace/session changes do not leak cached or late tenant data**: Verified both on the backend (sequential requests) and frontend (Vitest test suite).
- [x] **Test path runs with synthetic local configuration and no real provider secrets**: All configurations use synthetic local test identities, ephemeral keys, and loopback endpoints.
- [x] **Documentation explains how developers and agents run BFF security verification locally**: Full instructions provided in this document and in `infra/docker/README.md`.
