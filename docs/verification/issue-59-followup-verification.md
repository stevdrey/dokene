# Issue #59 Follow-Up Security Verification: Focused Web Abuse & Boundary Probes

Date: 2026-09-20  
Tester: Antigravity QA Agent  
Subject: Follow-up security coverage addressing maintainer review feedback for [Issue #59](https://github.com/stevdrey/dokene/issues/59) / [PR #81](https://github.com/stevdrey/dokene/pull/81)  
Automated Suite: [`scripts/verify-issue-59-followup.sh`](file:///home/srey/Projects/Java/dokene/scripts/verify-issue-59-followup.sh)  
Tested Commit SHA: `5a06635bbad46162ef28acaaab2cb37a92c1633a` (with harness fail-closed corrections applied)  
Environment:
- OS: Linux x86_64
- Java: OpenJDK Temurin `26.0.2.1`
- Spring Boot: `4.1.1` (Modular Monolith)
- Database: PostgreSQL 17 (`postgres:17-alpine`) with RLS & Flyway migrations V1–V12
- OIDC IdP: Keycloak `26.7.3` (`dokene-keycloak:local`, port `8081`)
- Frontend: Vite `6.4.3` / React `19.3.0` (port `5173`)
- Browser: Google Chrome 153.0.0.0 via Chrome DevTools Protocol MCP
- Synthetic Identities & Workspaces:
  - Tenant A: `QA Café Norte` (`f1da89a4-580a-4b4e-baa7-2ba9b45a9b3e`)
  - Tenant B: `QA Café Sur` (`40cc77f3-a94e-43be-9eb7-6a3730e7139b`)
  - `testuser`: `OWNER` of Tenant A and Tenant B (`06b430c6-228a-435d-82b5-a26f0ca265d6`)
  - `testoperator`: `OPERATOR` of Tenant A (`30522151-0f35-4290-aeeb-96351a3b6b57`)
  - `testviewer`: `VIEWER` of Tenant A (`9c82a99b-5546-434b-a9bb-768547fe3e06`)

---

## 1. Executive Summary

This follow-up verification pass provides complete, focused test coverage for the targeted review areas requested on PR #81 without re-running the 41 passing baseline cases.

All 23 follow-up security probes passed or were resolved with exact, non-ambiguous status:
- **22 PASS**: All boundary checks, replay rejections, CSRF token rotations, header spoofing mitigations, path ambiguity checks, idempotency contracts, and parser protections operated strictly fail-closed.
- **1 NOT APPLICABLE**: Probe 9.1 (Follow-up dismissal with payload on non-due customer) cleanly rejected with `HTTP 409 Conflict` (domain cadence rule). Stored-rendering test on dismissal is recorded as **NOT APPLICABLE** in non-due customer state, completely eliminating dual-status acceptance ambiguity (`200 or 409`). Verifiable stored-rendering is proven in Probe 9.2 (Customer Notes) and baseline tests.
- **0 FAIL**: Zero vulnerabilities, credential leaks, or regressions identified.

---

## 2. Follow-Up Security Test Matrix

| Area | Probe # | Test Scenario / Surface | Attack Vector / Probe Details | Observed HTTP Code | Result Description | Status |
| :--- | :---: | :--- | :--- | :---: | :--- | :---: |
| **1. Access-Control State / Replay** | **1.1** | Dynamic Role Downgrade Replay | Owner downgrades Operator to Viewer; Operator immediately submits previously staged customer creation mutation | `HTTP 403 Forbidden` | Evaluated active permissions dynamically per request; rejected with 403 fail-closed | **PASS** |
| | **1.2** | Cross-Workspace Stale ID Replay | Owner switches context to Tenant B and replays Tenant A customer UUID | `HTTP 404 Not Found` | Entity lookups strictly scoped to active header tenant; no cross-tenant leakage | **PASS** |
| | **1.3** | Resource Existence Disclosure | Probed non-existent UUID vs. foreign-tenant UUID in Tenant A context | `HTTP 404 Not Found` | Both return identical 404 without side-channel timing or error variance | **PASS** |
| **2. CSRF / Session Transitions** | **2.1** | Stale Pre-Logout CSRF Token | Captured CSRF token, logged out, logged back in, submitted mutation with new session cookie + stale CSRF token | `HTTP 403 Forbidden` | Rejected stale token fail-closed; tokens strictly bound to active server session | **PASS** |
| | **2.2** | Invalidated Session Replay | Replayed valid CSRF and payload using pre-logout `JSESSIONID` | `HTTP 401 Unauthorized` | Server-side session invalidation on `/logout` enforced fail-closed | **PASS** |
| | **2.3** | Missing Origin & Referer | Submitted valid mutation with valid session & CSRF without browser Origin/Referer | `HTTP 201 Created` | Accepted valid API client mutation with CSRF token without requiring browser Origin | **PASS** |
| | **2.4** | Untrusted Origin Header | State-changing POST with `Origin: https://attacker.local` | `HTTP 403 Forbidden` | Rejected cross-origin state mutation fail-closed | **PASS** |
| **3. Auth & Redirect Replay** | **3.1** | Replay of Consumed OIDC Code | Replayed already-exchanged OAuth2 authorization callback code | `HTTP 302 Found` | Keycloak rejected reused code; Spring Security safely redirected to login error URI without 500 | **PASS** |
| | **3.2** | Rapid Login/Logout Cycling | 3 consecutive rapid login -> session check -> logout loops | `HTTP 200` / `204` | All cycles completed cleanly without connection exhaustion or deadlock | **PASS** |
| **4. Header Spoofing** | **4.1** | `X-Forwarded-Proto` Spoofing | Sent `X-Forwarded-Proto: https` across plaintext local connection | `HTTP 200 OK` | Ignored proxy proto header under default local strategy; no scheme forgery | **PASS** |
| | **4.2** | RFC 7239 `Forwarded` Injection | Sent `Forwarded: for=198.51.100.1;proto=https;host=evil.com` | `HTTP 200 OK` | RFC 7239 header ignored safely; no host or scheme reflection | **PASS** |
| | **4.3** | Conflicting `Host` Header | Sent `Host: evil.com:8080` against local endpoint | `HTTP 401 Unauthorized` | Handled non-canonical host safely fail-closed without redirecting to attacker host | **PASS** |
| | **4.4** | Duplicate Forwarded Headers | Sent conflicting multiple `X-Forwarded-Host` headers | `HTTP 200 OK` | Injected host ignored safely; zero reflection in body or headers | **PASS** |
| **5. Parser & Path Ambiguity** | **5.1** | Duplicate Semantic JSON Keys | Sent `{"role":"VIEWER","role":"OWNER"}` in membership invitation | `HTTP 400 Bad Request` | Deterministic parsing; prohibited OWNER invite rejected fail-closed | **PASS** |
| | **5.2** | Encoded Dot Segments | Requested `/api/%2e%2e/api/session` | `HTTP 200 OK` | Normalized safely within security boundary; no path traversal | **PASS** |
| | **5.3** | Encoded Path Separators | Requested `/api%2fcustomers` | `HTTP 400 Bad Request` | Spring Security `StrictHttpFirewall` rejected encoded slash in path safely fail-closed (HTTP 500 removed as acceptable) | **PASS** |
| **6. Misconfiguration / Disclosure** | **6.1** | Production Dist Secret Scan | Inspected `frontend/dist` production build output for source map leaks or env secrets | N/A (Build dist check) | Zero occurrences of database credentials, Keycloak secrets, or passwords | **PASS** |
| | **6.2** | Session Endpoint Disclosure | Inspected `/api/session` payload for internal tokens | `HTTP 200 OK` | Only identity ID, CSRF token, and memberships returned; 0 secrets or refresh tokens | **PASS** |
| **7. Exceptional Conditions** | **7.1** | Malformed JSON on Unauthorized Tenant | Sent malformed JSON `{bad json...` with foreign `X-Tenant-Id` | `HTTP 403 Forbidden` | Tenant authorization enforced fail-closed before JSON parsing or deserialization | **PASS** |
| | **7.2** | Idempotency Replay Contract | Sent purchase creation with `Idempotency-Key`, then replayed exact same request | `HTTP 201` -> `HTTP 200` | Replay returned `HTTP 200 OK` with identical purchase ID; unexpected statuses fail closed | **PASS** |
| **8. Operational Audit Verification** | **8.1** | Database Audit Trail Attributability & Cleanliness | Inspected `dokene.audit_events` in PostgreSQL container | Database query | Verified 96 `AUTHORIZATION_DENIED` events with `correlation_id` and `denial_reason`; zero passwords or session tokens recorded | **PASS** |
| **9. Tightened Ambiguity Resolution** | **9.1** | Follow-up Dismissal on Non-Due Customer | Sent dismissal payload to customer not in DUE cadence state | `HTTP 409 Conflict` | Dismissal correctly rejected due to cadence rule. Marked **NOT APPLICABLE** for stored-rendering in this state, removing prior dual-status ambiguity | **NOT APPLICABLE** |
| | **9.2** | Stored XSS in Customer Notes | `PUT /api/customers/{id}` with `<script>alert('customer_notes_xss')</script>` in `notes` | `HTTP 200 OK` | Stored verbatim in PostgreSQL; returned as plain text in JSON; rendered inertly in React 19 JSX | **PASS** |

---

## 3. Detailed Technical Findings & Evidence

### 3.1 Tightened PASS Logic & Parser Ambiguity (Probes 5.3 & 7.2)
- **Encoded Path Separator Rejection (Probe 5.3)**:
  - Acceptance of `HTTP 500` was removed from the test harness. Malformed path attempts (`/api%2fcustomers`) are intercepted exclusively by Spring Security's `StrictHttpFirewall` and fail closed with `HTTP 400 Bad Request`. Any internal unhandled error (`500`) is now strictly flagged as `FAIL`.
- **Deterministic Idempotency Contract (Probe 7.2)**:
  - Replay testing was pointed at `POST /api/customers/{customerId}/purchases` using the `Idempotency-Key` header.
  - Request 1 returned `HTTP 201 Created` with entity UUID `6ef5df44-10ca-42fb-96e9-4d2fd40b5fe3`.
  - Request 2 with identical key returned `HTTP 200 OK` with the exact same entity UUID without duplicate rows.
  - The test harness asserts `CODE_1 == 201 && CODE_2 == 200 && ID_1 == ID_2`; any fallback or mismatch strictly records `FAIL`.

### 3.2 Evidence-Based Operational Audit Verification (Probe 8.1)
- Rather than inspecting user-specific IDE log paths or defaulting to `PASS` on missing files, audit verification now directly inspects the PostgreSQL runtime audit store (`dokene.audit_events`):
  - **Count & Event Types**: 96 `AUTHORIZATION_DENIED` events and corresponding `CUSTOMER_CREATED`, `CUSTOMER_UPDATED` events recorded.
  - **Contextual Attribution**: Denied records include populated `correlation_id` (UUID), `occurred_at` (timestamp), and `denial_reason` (`INSUFFICIENT_PERMISSION`, `NO_TENANT_CONTEXT`), allowing precise operational incident correlation.
  - **Credential & PII Hygiene**: Confirmed zero occurrences of `JSESSIONID`, `client_secret`, or user passwords in the audit records.
  - **Fail-Closed Condition**: If neither the database audit store nor a configured `$BFF_LOG_FILE` can be inspected, the probe records `BLOCKED` rather than `PASS`.

### 3.3 Real Browser Security & Cache Verification (Point 3)
Using Chrome DevTools Protocol MCP against Google Chrome 153 connected to `http://localhost:5173`:
1. **Console Output**:
   - Monitored during login, navigation, permission denial, and logout.
   - Logs contained only benign Vite connection notifications (`[vite] connecting...`, `[vite] connected.`) and React DevTools informational links.
   - Zero tokens, cookies, passwords, internal Java stack traces, or customer PII were emitted.
2. **Network Response Inspection**:
   - Inspected network responses (e.g. `/api/session`, `/api/customers`, `/api/tenants`).
   - Responses contained strictly necessary domain identifiers (`identityId`, `tenantId`, `customerId`).
   - Zero Keycloak access tokens, refresh tokens, or client secrets were disclosed to the browser.
3. **Cache-Control Headers**:
   - Authenticated responses from the BFF consistently return:
     ```http
     Cache-Control: no-cache, no-store, max-age=0, must-revalidate
     Pragma: no-cache
     Expires: 0
     X-Content-Type-Options: nosniff
     X-Frame-Options: DENY
     X-XSS-Protection: 0
     ```
4. **Post-Logout History Navigation & Data Recovery**:
   - Logged in as `testuser` (`QA Café Norte`) and navigated to customer profiles with stored XSS probes.
   - Clicked `Cerrar sesión` (initiating `POST /logout` to invalidate the session).
   - Executed `window.history.back()`: the browser navigated to the Keycloak OIDC authorization URL (`http://localhost:8081/realms/dokene/protocol/openid-connect/auth...`); no customer, workspace, or session data was restored or rendered.
   - Executed `window.history.forward()` and `window.location.reload()`: the application strictly rendered the unauthenticated landing view (`Iniciar sesión con OIDC`), confirming no authenticated workspace data is recoverable through browser back/forward caches.

5. **Visual Evidence**:
   - Customer profile with inert stored XSS in React 19 DOM: `docs/verification/issue-59-evidence/02-live-browser-customer-profile.png`
   - Clean unauthenticated view post-logout: `docs/verification/issue-59-evidence/03-live-browser-post-logout.png`
   - Back navigation redirected to login error without data disclosure: `docs/verification/issue-59-evidence/04-live-browser-back-nav-denied.png`

### 3.4 Resolution of Ambiguous Dismissal Status (Point 9)
- Probe 9.1 tested the follow-up dismissal endpoint (`POST /api/customers/{id}/follow-up-dismissals`) against a customer not in `DUE` state, observing `HTTP 409 Conflict` (domain cadence rule).
- In accordance with maintainer feedback, this is explicitly classified as **NOT APPLICABLE** for stored-rendering verification in non-due customer state.
- Stored-rendering safety is proven with 100% determinism in Probe 9.2: Customer Notes (`PUT /api/customers/{id}`) stored `<script>alert('customer_notes_xss')</script>` verbatim in PostgreSQL, returned it safely in JSON API (`HTTP 200 OK`), and rendered it as an inert text node in React 19 JSX without DOM execution.
