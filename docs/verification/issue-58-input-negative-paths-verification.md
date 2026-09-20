# Issue #58 Manual QA Verification: Input Handling, Negative Paths and Actionable Error Feedback

Date: 2026-09-20  
Tester: Antigravity QA Agent  
Commit Tested: `24bab9d06b83d64185136adc6961a69b5f5a8e8e` (branch `main`)  
Environment:
- OS: Linux
- Java: Temurin 26.0.2.1
- Spring Boot: 4.1.1 (Modular Monolith)
- Database: PostgreSQL 17 (Containerized `postgres:17-alpine`) with Flyway migrations V1 through V12
- OIDC Identity Provider: Keycloak 26.7.3 (Containerized `quay.io/keycloak/keycloak:26.7.3`) with imported `dokene` realm
- Browser: Google Chrome 153.0.0.0 via Chrome DevTools Protocol MCP
- Test Workspace: `QA Café Norte` (Tenant ID `f1da89a4-580a-4b4e-baa7-2ba9b45a9b3e`)
- Authenticated Identities:
  - `testuser` (`OWNER`, identity `06b430c6-228a-435d-82b5-a26f0ca265d6`)
  - `testoperator` (`OPERATOR`, identity `30522151-0f35-4290-aeeb-96351a3b6b57`)
  - `testviewer` (`VIEWER`, identity `9c82a99b-5546-434b-a9bb-768547fe3e06`)

---

## 1. Executive Summary

This manual exploratory QA pass and follow-up regression suite fulfills all acceptance criteria and maintainer review feedback for [Issue #58](https://github.com/stevdrey/dokene/issues/58). Testing focused on real external contracts, end-to-end negative input scenarios, edge cases, boundaries, data preservation, user-facing feedback quality, multi-user membership RBAC, idempotency guarantees, and protocol robustness.

A total of **60 scenarios** were executed:
- **8 UI-level exploratory scenarios** operated directly in Chrome DevTools MCP.
- **21 baseline API protocol & parameter boundary scenarios** executed with real browser sessions and CSRF tokens.
- **31 follow-up regression scenarios** executed against the live application via `./scripts/verify-issue-58-followup.sh`.

### Key Quality Observations & Verification:
1. **Zero 500 Internal Server Errors & Zero Information Leaks**: No malformed JSON, wrong types, missing headers, SQL injection tokens, boundary values, or protocol mutations triggered an HTTP 500 error, stack trace, class name, or SQL exception in client responses.
2. **Form Data Preservation**: Across all client validation rejections in `CustomerFormModal`, valid user-entered values (names, phone numbers, notes) remained intact in the DOM, preventing loss of work.
3. **Compound-Invalid Scenarios & Remediation of Defect #1 (PR #73 / Issue #72)**:
   - Previously, entering an invalid phone length for the region (e.g. `123` for Chile `+56`) resulted in a generic `"Error HTTP 400"`.
   - In PR #73 (commit `2c4dde7`), pre-submit regional phone validation was implemented in `CustomerFormModal.tsx`.
   - Verified on live commit `24bab9d06b`: A compound invalid submission (whitespace name + invalid phone `123` + 2000-character notes) correctly blocks submission client-side, renders an accessible field-level error linked via `aria-describedby` directly below the phone input (*"El número ingresado no es válido para la región seleccionada (Chile requiere 9 dígitos)."*), and displays the notes character counter `2000/500`. Visual proof captured in `07-compound-invalid-form-validation.png`.
4. **Multi-User & Membership RBAC Negative Scenarios**:
   - Tested using pre-provisioned synthetic identities (`testuser` OWNER, `testoperator` OPERATOR, `testviewer` VIEWER) in tenant `QA Café Norte`.
   - Unauthorized invite attempts by `OPERATOR` and `VIEWER` fail-closed with HTTP `403 Forbidden`.
   - Role change by `OPERATOR` fails-closed with HTTP `403 Forbidden`.
   - Revocation attempt by `VIEWER` fails-closed with HTTP `403 Forbidden`.
   - Direct invite of prohibited `OWNER` role rejected with HTTP `400 Bad Request`.
   - Duplicate membership invitation for existing member rejected with HTTP `409 Conflict`.
   - Role update and revocation for nonexistent identities fail-closed with HTTP `404 Not Found`.
   - Persisted membership state remained exactly 3 members with no integrity corruption.
5. **Purchase History Boundaries & Idempotency**:
   - Malformed timestamp strings rejected with HTTP `400`.
   - Timezone-offset variants (e.g. `+05:30`) accepted and normalized into ISO 8601 (HTTP `201`).
   - Descriptions exceeding 500 chars (501 chars) rejected with HTTP `400`.
   - Idempotency replay with identical payload returns existing purchase (HTTP `200 OK`) without duplicate rows.
   - Idempotency replay with conflicting payload rejected with HTTP `409 Conflict`.
   - Stale `If-Match` on correction and void rejected with HTTP `409 Conflict`.
   - Valid void operation via `DELETE /api/customers/{cid}/purchases/{pid}` completes with HTTP `204 No Content`.
   - Voiding an already voided purchase with prior version rejected with HTTP `409 Conflict`.
   - Persisted valid purchase count remained exactly 1.
6. **Consent & Do-Not-Contact Negative Paths**:
   - Stale consent version update rejected with HTTP `409 Conflict`.
   - Invalid enum transition rejected with HTTP `400 Bad Request` without mutating persisted state.
7. **Follow-Up Negative & Stale Actions**:
   - Snoozing with a past date rejected with HTTP `400 Bad Request` ("Snooze date cannot be in the past").
   - Follow-up completion on an ineligible customer (e.g. opted-out) rejected with HTTP `409 Conflict`.
   - Idempotency key replay returns existing completion with HTTP `200 OK`.
   - Stale `If-Match` on manual follow-up rejected with HTTP `409 Conflict`.
8. **Protocol & Query Gaps**:
   - Missing `Content-Type` on mutation rejected with HTTP `415 Unsupported Media Type`.
   - Unsupported `Content-Type: application/xml` rejected with HTTP `415`.
   - Unsupported `Accept: application/xml` rejected with HTTP `406 Not Acceptable`.
   - Parameter pollution (`?status=DUE&status=OVERDUE`) handled cleanly with HTTP `400 Bad Request`.
   - Extreme 1000-character search queries handled cleanly with HTTP `200 OK` (empty results).

---

## 2. Test Execution Matrix

### 2.1 UI-Level Exploratory Tests (Chrome DevTools MCP)

| ID | Feature Area | Scenario & Input Tested | Expected Behavior | Observed Behavior | Status | Visual Evidence |
|:---|:---|:---|:---|:---|:---:|:---|
| **UI-01** | Customer Form | Whitespace-only display name (`"   "`) | Validation blocks submission; displays localized error; preserves entered phone | UI alert displays: `El nombre del cliente es obligatorio.`; phone number preserved; no network call | **PASS** | `01-customer-empty-name-validation.png` |
| **UI-02** | Customer Form | Display name exceeding 160 characters (161 chars) | Blocked with localized error; character counter reflects excess | UI alert displays: `El nombre no puede superar los 160 caracteres.`; counter shows `161/160` | **PASS** | `02-customer-long-name-validation.png` |
| **UI-03** | Customer Form | Unparseable / short phone number for region (`123` with Chile `+56`) | Specific validation error explaining number format requirements | Backend returns `400 Bad Request`; UI modal displays generic: `Error HTTP 400`. Entered name and phone are preserved. | **WARN** *(Resolved in #72)* | `03-customer-invalid-phone-error-400.png` |
| **UI-04** | Customer Form | Complex Unicode: Arabic RTL, accents, emojis (`صالح José ☕ Gómez`) | Created successfully, rendered cleanly across all screens | Profile rendered with full Unicode text intact; contact policy and WhatsApp consent cards initialized cleanly | **PASS** | `04-customer-unicode-success.png` |
| **UI-05** | Customer Form | Duplicate normalized phone in same workspace (`+56987654321`) | Blocked with 409 Conflict; displays actionable conflict feedback; form data preserved | Backend returns 409 Conflict; UI renders: `Conflicto: el registro o número de contacto ya existe o está en conflicto.`; name and phone retained | **PASS** | `05-customer-duplicate-phone-conflict.png` |
| **UI-06** | Purchases | Purchase description boundary (500 chars) & void workflow | 500-char description saved; voiding updates status to `Anulada` and recalculates latest purchase | Description accepted; void modal cleanly marks purchase as `Anulada`; latest purchase summary immediately updates to `Sin compras registradas` | **PASS** | `04-customer-unicode-success.png` |
| **UI-07** | Follow-Up Search | Special characters & SQL injection tokens in search query (`'; DROP TABLE...`) | Handled safely via parameterized query; no errors or unhandled exceptions | UI gracefully shows empty state: `No se encontraron resultados para "'; DROP TABLE..."` | **PASS** | `06-followup-safe-query-handling.png` |
| **UI-08** | Compound Form | Whitespace name + invalid phone (`123` for Chile `+56`) + 2000 chars notes | Multiple field errors identified simultaneously; accessible error linked via `aria-describedby`; form data retained | Pre-submit validation blocks request. Field-level error appears below phone input (*"El número ingresado no es válido para la región seleccionada (Chile requiere 9 dígitos)."*). Character counter displays `2000/500`. Work preserved. | **PASS** *(Fix for #72 verified)* | `07-compound-invalid-form-validation.png` |

---

### 2.2 Baseline API Protocol & Parameter Boundary Tests

| ID | Category | Endpoint & Request | Expected HTTP | Observed HTTP | Security / Leak Check | Status |
|:---|:---|:---|:---:|:---:|:---|:---:|
| **API-01** | Request-shape | `POST /api/customers` with broken JSON syntax | 400 | **400** | Clean empty body; zero stack trace | **PASS** |
| **API-02** | Request-shape | `POST /api/customers` with empty body | 400 | **400** | Zero stack trace; no 500 | **PASS** |
| **API-03** | Request-shape | `POST /api/customers` with wrong types (`phones: "not_array"`) | 400 | **400** | Zero class or framework leakage | **PASS** |
| **API-04** | Mass Assignment | `POST /api/customers` with unknown extra fields (`isAdmin: true`, `role: SUPERUSER`, `tenantId: ...`) | Ignored safely or 400 | **201** | Extra fields completely ignored; no privilege escalation | **PASS** |
| **API-05** | Protocol | `PATCH /api/customers` (Unsupported HTTP Method) | 403 / 405 | **403** | Blocked by CORS/Security (`Invalid CORS request`); no leak | **PASS** |
| **API-06** | Protocol | `DELETE /api/follow-up-queue` (Unsupported HTTP Method) | 405 | **405** | Returned `Method Not Allowed` with clean JSON timestamp | **PASS** |
| **API-07** | Identifiers | `GET /api/customers/not-a-valid-uuid` (Malformed UUID) | 400 | **400** | Handled cleanly; no NumberFormatException or UUID trace | **PASS** |
| **API-08** | Identifiers | `GET /api/customers/00000000-0000-0000-0000-000000000000` (Nonexistent UUID) | 404 | **404** | Clean fail-closed 404 Not Found | **PASS** |
| **API-09** | Pagination | `GET /api/customers?limit=-5` (Negative page limit) | 400 | **400** | Validated and rejected cleanly | **PASS** |
| **API-10** | Pagination | `GET /api/customers?limit=0` (Zero page limit) | 400 | **400** | Validated and rejected cleanly | **PASS** |
| **API-11** | Pagination | `GET /api/customers?limit=99999` (Excessive limit >100) | 400 | **400** | Validated and rejected cleanly | **PASS** |
| **API-12** | Pagination | `GET /api/customers?cursor=INVALID_BASE64_TOKEN!!` | 400 | **400** | Validated and rejected cleanly | **PASS** |
| **API-13** | Concurrency | `PUT /api/customers/{id}` with stale `If-Match: "99999"` | 409 | **409** | Optimistic concurrency conflict cleanly detected | **PASS** |
| **API-14** | Concurrency | `PUT /api/customers/{id}` with missing `If-Match` header | 400 | **400** | Mandatory version header enforced | **PASS** |
| **API-15** | Consent | `PUT .../consents/WHATSAPP` with status `UNKNOWN` | 400 | **400** | UNKNOWN status rejected | **PASS** |
| **API-16** | Consent | `PUT .../consents/TELEGRAM` (Unsupported channel) | 400 | **400** | Unsupported channel rejected | **PASS** |
| **API-17** | Consent | `PUT .../do-not-contact` with missing audit source | 400 | **400** | Mandatory audit source enforced | **PASS** |
| **API-18** | Consent | `PUT .../do-not-contact` with valid `CUSTOMER_VERBAL` source | 200 | **200** | Policy updated; `contact-eligibility` immediately yields `DO_NOT_CONTACT` | **PASS** |
| **API-19** | Follow-Up | `POST .../manual-follow-ups` with malformed `Idempotency-Key` (spaces) | 400 | **400** | Regex validation `[A-Za-z0-9._:-]{1,128}` enforced | **PASS** |
| **API-20** | Follow-Up | `POST .../manual-follow-ups` with notes >500 chars (501 chars) | 400 | **400** | Length limit enforced | **PASS** |
| **API-21** | Follow-Up | `POST /api/customers/{fake_id}/follow-up-dismissals` | 404 | **404** | Nonexistent resource cleanly rejected | **PASS** |

---

### 2.3 Follow-Up Automated Regression Suite (`scripts/verify-issue-58-followup.sh`)

Executed against live environment at commit `24bab9d06b83d64185136adc6961a69b5f5a8e8e`:

| ID | Category | Scenario & Target | Expected HTTP | Observed HTTP | Status | Details |
|:---|:---|:---|:---:|:---:|:---:|:---|
| **F-01** | Membership | Unauthorized invite by OPERATOR | 403 | **403** | **PASS** | OPERATOR cannot invite members (fail-closed 403) |
| **F-02** | Membership | Unauthorized invite by VIEWER | 403 | **403** | **PASS** | VIEWER cannot invite members (fail-closed 403) |
| **F-03** | Membership | Role change by non-owner (OPERATOR) | 403 | **403** | **PASS** | Role change rejected for OPERATOR (fail-closed 403) |
| **F-04** | Membership | Revocation attempt by VIEWER | 403 | **403** | **PASS** | Revocation rejected for VIEWER (fail-closed 403) |
| **F-05** | Membership | Invite prohibited OWNER role | 400 | **400** | **PASS** | OWNER role invitation rejected with 400 |
| **F-06** | Membership | Duplicate membership invitation | 409 | **409** | **PASS** | Duplicate membership rejected with 409 Conflict |
| **F-07** | Membership | Update nonexistent membership | 404 | **404** | **PASS** | Nonexistent membership returned 404 Not Found |
| **F-08** | Membership | Revoke nonexistent membership | 404 | **404** | **PASS** | Nonexistent revocation returned 404 Not Found |
| **F-09** | Membership | Persisted membership state integrity | 3 | **3** | **PASS** | Total memberships remained exactly 3 (Owner, Operator, Viewer) |
| **F-10** | Purchase | Malformed timestamp format | 400 | **400** | **PASS** | Invalid timestamp rejected with 400 |
| **F-11** | Purchase | Timezone-offset variant (+05:30) | 201 | **201** | **PASS** | Accepted and normalized ISO 8601 offset |
| **F-12** | Purchase | Description exceeding max (501 chars) | 400 | **400** | **PASS** | 501-char description rejected with 400 |
| **F-13** | Purchase | Idempotent replay with identical payload | 200 | **200** | **PASS** | First request returned 201, replay returned 200 OK without duplicate |
| **F-14** | Purchase | Conflicting payload with same Idempotency-Key | 409 | **409** | **PASS** | Conflicting purchase replay returns 409 Conflict |
| **F-15** | Purchase | Stale If-Match conflict on correction | 409 | **409** | **PASS** | Stale version on correction rejected with 409 Conflict |
| **F-16** | Purchase | Stale If-Match conflict on void | 409 | **409** | **PASS** | Stale version on void rejected with 409 Conflict |
| **F-17** | Purchase | Valid purchase void (`DELETE /.../purchases/{pid}`) | 204 | **204** | **PASS** | Purchase successfully voided (204 No Content) |
| **F-18** | Purchase | Voiding already voided purchase (with prior version) | 409 | **409** | **PASS** | Re-voiding with prior version rejected with 409 Conflict |
| **F-19** | Purchase | Purchase state integrity intact | 1 | **1** | **PASS** | Exactly 1 valid purchase persisted (timezone offset test) |
| **F-20** | Consent | Stale version on consent update | 409 | **409** | **PASS** | Stale ETag returns 409 Conflict |
| **F-21** | Consent | Invalid status rejected without mutation | 400 | **400** | **PASS** | Rejected with 400 and status remained UNKNOWN |
| **F-22** | FollowUp | Past snooze date rejected | 400 | **400** | **PASS** | Snooze date in the past rejected with 400 Bad Request |
| **F-23** | FollowUp | Follow-up on ineligible customer (opted-out) | 409 | **409** | **PASS** | Manual follow-up on ineligible customer rejected with 409 Conflict |
| **F-24** | FollowUp | Idempotent retry returns existing completion | 200 | **200** | **PASS** | First request returned 201, replay returned 200 OK without duplicate |
| **F-25** | FollowUp | Stale If-Match on manual follow-up | 409 | **409** | **PASS** | Stale policy version rejected with 409 Conflict |
| **F-26** | Compound | Malformed date + missing If-Match | 400 | **400** | **PASS** | Compound invalid request rejected cleanly with 400 |
| **F-27** | Protocol | Missing Content-Type on mutation | 415 | **415** | **PASS** | Rejected with HTTP 415 |
| **F-28** | Protocol | Incorrect Content-Type (application/xml) | 415 | **415** | **PASS** | Rejected with HTTP 415 |
| **F-29** | Protocol | Unusual Accept header (application/xml) | 406 | **406** | **PASS** | Handled cleanly without 500: HTTP 406 |
| **F-30** | Protocol | Parameter pollution (?status=DUE&status=OVERDUE) | 400 | **400** | **PASS** | Handled safely without 500: HTTP 400 |
| **F-31** | Protocol | Extremely long search query (1000 chars) | 200 | **200** | **PASS** | Handled cleanly without 500: HTTP 200 |

---

## 3. Defect & Remediation Status

### Defect #1: Generic "Error HTTP 400" on Phone Validation Rejection (RESOLVED)
- **Original Finding**: Entering a phone number with invalid length for the region (e.g. `123` for Chile `+56`) resulted in backend `400 Bad Request` with an empty body, rendered as generic `"Error HTTP 400"` in the UI modal.
- **Resolution**: Tracked in [Issue #72](https://github.com/stevdrey/dokene/issues/72) and fixed in [PR #73](https://github.com/stevdrey/dokene/pull/73) (`2c4dde7`).
- **Verification on main (`24bab9d`)**:
  - `CustomerFormModal.tsx` validates phone numbers against country calling code rules before submission.
  - An accessible error message is rendered directly below the input field and linked via `aria-describedby="phone-error"`:
    *"El número ingresado no es válido para la región seleccionada (Chile requiere 9 dígitos)."*
  - Tested in both isolated and compound-invalid scenarios (see UI-08 and `07-compound-invalid-form-validation.png`).
  - Work and entered field values remain preserved in form state.

---

## 4. Visual Evidence Index

All screenshots captured during testing are stored in `docs/verification/issue-58-evidence/`:
1. `01-customer-empty-name-validation.png` — Client-side validation for empty/whitespace customer name.
2. `02-customer-long-name-validation.png` — Client-side validation and dynamic counter for >160 characters.
3. `03-customer-invalid-phone-error-400.png` — Backend 400 rejection rendered as generic error in UI (pre-fix baseline).
4. `04-customer-unicode-success.png` — Full Unicode (Arabic RTL, accents, emoji) customer profile view.
5. `05-customer-duplicate-phone-conflict.png` — Actionable conflict feedback on duplicate phone submission.
6. `06-followup-safe-query-handling.png` — Follow-up queue search handling SQL injection and special characters safely.
7. `07-compound-invalid-form-validation.png` — Compound-invalid scenario verifying resolution of Issue #72 / PR #73 (accessible field error and counter).
