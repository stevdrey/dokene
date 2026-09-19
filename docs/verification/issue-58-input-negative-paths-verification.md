# Issue #58 Manual QA Verification: Input Handling, Negative Paths and Actionable Error Feedback

Date: 2026-09-19
Tester: Antigravity QA Agent
Environment:
- OS: Linux
- Java: Temurin 26.0.2.1
- Spring Boot: 4.1.1 (Modular Monolith)
- Database: PostgreSQL 17 (Containerized `postgres:17-alpine`) with Flyway migrations V1 through V12
- OIDC Identity Provider: Keycloak 26.7.3 (Containerized `quay.io/keycloak/keycloak:26.7.3`) with imported `dokene` realm
- Browser: Google Chrome 153.0.0.0 via Chrome DevTools Protocol MCP
- Test Workspace: `QA Café Norte` (Tenant ID `f1da89a4-580a-4b4e-baa7-2ba9b45a9b3e`)
- Authenticated User: `testuser` (`OWNER`)

---

## 1. Executive Summary

This manual exploratory QA pass fulfills the acceptance criteria for [Issue #58](https://github.com/stevdrey/dokene/issues/58). Testing focused on real external contracts, end-to-end negative input scenarios, edge cases, boundaries, data preservation, and user-facing feedback quality.

A total of **28 exploratory scenarios** were executed:
- **7 UI-level exploratory scenarios** operated directly in Chrome DevTools MCP.
- **21 API protocol, request-shape, and parameter boundary scenarios** executed with real browser sessions and CSRF tokens.

### Key Quality Observations:
1. **Zero 500 Internal Server Errors & Zero Information Leaks**: No malformed JSON, wrong types, missing headers, SQL injection tokens, or boundary values triggered an HTTP 500 error, stack trace, class name, or SQL exception in client responses.
2. **Form Data Preservation**: Across all client validation rejections in `CustomerFormModal`, valid user-entered values (names, phone numbers, notes) remained intact in the DOM, preventing loss of work.
3. **Actionable vs Generic Error Feedback**:
   - Empty name and excessive name length (>160 chars) trigger immediate, localized, accessible feedback with dynamic character counters.
   - Phone conflict returns an explicit, user-friendly conflict message: *"Conflicto: el registro o número de contacto ya existe o está en conflicto."*
   - **Quality Defect Identified**: When submitting a phone number with invalid length for the region (e.g. `123` for Chile `+56`), the backend returns `400 Bad Request` with an empty body (`ResponseEntity<Void>`), and the modal displays only a generic alert: **`"Error HTTP 400"`**. This fails the criteria of identifying the problematic field and explaining how to correct the input.
4. **Unicode & Internationalization Robustness**: Names and notes with Arabic RTL script, Spanish accents (`ñ`, `á`, `é`), and emojis (`☕`, `🎉`) were accepted, saved, and rendered accurately across the entire UI and backend without character corruption.

---

## 2. Test Execution Matrix

### 2.1 UI-Level Exploratory Tests (Chrome DevTools MCP)

| ID | Feature Area | Scenario & Input Tested | Expected Behavior | Observed Behavior | Status | Visual Evidence |
|:---|:---|:---|:---|:---|:---:|:---|
| **UI-01** | Customer Form | Whitespace-only display name (`"   "`) | Validation blocks submission; displays localized error; preserves entered phone | UI alert displays: `El nombre del cliente es obligatorio.`; phone number preserved; no network call | **PASS** | `01-customer-empty-name-validation.png` |
| **UI-02** | Customer Form | Display name exceeding 160 characters (161 chars) | Blocked with localized error; character counter reflects excess | UI alert displays: `El nombre no puede superar los 160 caracteres.`; counter shows `161/160` | **PASS** | `02-customer-long-name-validation.png` |
| **UI-03** | Customer Form | Unparseable / short phone number for region (`123` with Chile `+56`) | Specific validation error explaining number format requirements | Backend returns `400 Bad Request`; UI modal displays generic: `Error HTTP 400`. Entered name and phone are preserved. | **WARN** *(Feedback Defect)* | `03-customer-invalid-phone-error-400.png` |
| **UI-04** | Customer Form | Complex Unicode: Arabic RTL, accents, emojis (`صالح José ☕ Gómez`) | Created successfully, rendered cleanly across all screens | Profile rendered with full Unicode text intact; contact policy and WhatsApp consent cards initialized cleanly | **PASS** | `04-customer-unicode-success.png` |
| **UI-05** | Customer Form | Duplicate normalized phone in same workspace (`+56987654321`) | Blocked with 409 Conflict; displays actionable conflict feedback; form data preserved | Backend returns 409 Conflict; UI renders: `Conflicto: el registro o número de contacto ya existe o está en conflicto.`; name and phone retained | **PASS** | `05-customer-duplicate-phone-conflict.png` |
| **UI-06** | Purchases | Purchase description boundary (500 chars) & void workflow | 500-char description saved; voiding updates status to `Anulada` and recalculates latest purchase | Description accepted; void modal cleanly marks purchase as `Anulada`; latest purchase summary immediately updates to `Sin compras registradas` | **PASS** | `04-customer-unicode-success.png` |
| **UI-07** | Follow-Up Search | Special characters & SQL injection tokens in search query (`'; DROP TABLE...`) | Handled safely via parameterized query; no errors or unhandled exceptions | UI gracefully shows empty state: `No se encontraron resultados para "'; DROP TABLE..."` | **PASS** | `06-followup-safe-query-handling.png` |

---

### 2.2 API Protocol & Request-Shape Boundary Tests

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

### 2.3 Cases Intentionally Skipped (Automated Coverage Verified)

| Component / Flow | Skipped Case | Rationale & Existing Automated Coverage |
|:---|:---|:---|
| `PhoneNormalizer` | Phone number parsing permutations across 30+ ISO country codes | Thoroughly covered in `PhoneNormalizerTest.java` (unit test suite tests all edge cases, prefixes, and NaNPA territory distinctions). Manual focus was reserved for external API/UI feedback. |
| `TenantContextRequestFilter` | Tampered HMAC signature in tenant context cookies | Covered deterministically in `BffTenantBoundarySecurityIntegrationTest.java` and `SignedTenantContextCodecTest.java`. |
| `CsrfFilter` | CSRF token absence vs. invalidity across all HTTP mutations | Covered exhaustively in `OidcBrowserSessionIntegrationTest.java` and verified in #52. |
| Database RLS | SQL tenant isolation bypass attempts | Covered by Testcontainers PostgreSQL RLS integration tests in `TenantIsolationSecurityIntegrationTest.java`. |

---

### 2.4 Blocked UI Scenarios Awaiting Implementation

| Scenario | Status | Reason / Tracking |
|:---|:---:|:---|
| Multi-user invitation & role revocation negative testing | **BLOCKED** | Local development environment only provisions single synthetic user (`testuser`). Multi-tenant membership invitation UI/API is tracked under **#66**. |
| Outbound WhatsApp messaging failure feedback | **BLOCKED** | Direct messaging integration is not yet connected; queue only supports disposition/manual logs. |
| Purchase monetary amount boundaries | **BLOCKED** *(N/A)* | Domain model records description, timestamps, and status; monetary amounts are not part of the active schema per #34. |

---

## 3. Defect & Usability Findings

### Defect #1: Generic "Error HTTP 400" on Phone Validation Rejection
- **Input**: Selecting region Chile (`+56`) and typing a 3-digit phone number `123`.
- **Observed Behavior**:
  - The backend returns `HTTP 400 Bad Request` with an empty response body (`ResponseEntity<Void>`).
  - The UI modal catches the `ApiError` and displays only: **`"Error HTTP 400"`**.
  - No indication is given that the phone number was the cause of the failure, nor does the UI explain that a Chilean mobile number requires 9 digits.
- **Recommended Remediation**:
  1. Add pre-submit phone format validation in `CustomerFormModal.tsx` matching the selected country calling code.
  2. Enhance backend `CustomerExceptionHandler` to return RFC 7807 `ProblemDetail` or a structured error response `{"field": "phones[0].number", "message": "El formato del teléfono es inválido para la región seleccionada."}` instead of an empty body.

---

## 4. Visual Evidence Index

All screenshots captured during testing are stored in `docs/verification/issue-58-evidence/`:
1. `01-customer-empty-name-validation.png` — Client-side validation for empty/whitespace customer name.
2. `02-customer-long-name-validation.png` — Client-side validation and dynamic counter for >160 characters.
3. `03-customer-invalid-phone-error-400.png` — Backend 400 rejection rendered as generic error in UI.
4. `04-customer-unicode-success.png` — Full Unicode (Arabic RTL, accents, emoji) customer profile view.
5. `05-customer-duplicate-phone-conflict.png` — Actionable conflict feedback on duplicate phone submission.
6. `06-followup-safe-query-handling.png` — Follow-up queue search handling SQL injection and special characters safely.
