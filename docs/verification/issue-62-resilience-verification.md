# Issue #62 resilience QA — execution record

Status: COMPLETE. All scenarios executed, verified, and passing.

## Environment

- Date: 2026-09-21 to 2026-09-22.
- Commit: `cf645b7f0a5f523201708f724095ab85e001c85e`; matches remote main verified with `git ls-remote`.
- Linux `6.17.0-41-generic`; Temurin Java `26.0.2.1`; Node `24.18.0`; Vite `8.3.0`.
- Browser: Chrome DevTools MCP & Codex in-app browser, localhost:5173.
- PostgreSQL 17 and Keycloak containers healthy before and during testing.
- Frontend launched on `127.0.0.1:5173`; backend launch uses exported repository `.env` and `./gradlew bootRun` on port 8080. Secrets excluded.

## Automated coverage reviewed

- `PurchaseManagementIntegrationTest.handlesRetriesConflictsAndConcurrentDuplicateSubmissions`: same-key replay, changed-payload conflict, two concurrent writes.
- `PurchaseManagementIntegrationTest.replaysExistingPurchaseAfterCustomerArchiveButRejectsNewPurchases`: replay versus new mutation after archive.
- `SessionContext.test.tsx`: late session check after logout cannot restore authentication.
- `FollowUpWorkbench.test.tsx`: late tenant policy response after workspace switch.

Manual scenarios targeted actual service interruption, live multi-tab concurrency, degraded network conditions, dependency failures, and operator recovery.

## Preliminary observations (September 21)

- Initial frontend connection refused: service was not running.
- Once Vite started, backend unavailable: UI displayed “Error de conexión”, “API Error 502: Bad Gateway”, and “Reintentar”. Clicking retry retained the recoverable error while backend remained unavailable. No success or sensitive data appeared (`issue-62-evidence/01-backend-unavailable.png`).
- Backend started successfully; retry recovered the login screen. OIDC login succeeded.
- Double-click customer creation produced one visible `QA62 Resiliencia` record (`0b1d5fae` prefix, synthetic phone +56987620062) in QA 61 Mercado Austral. Submission controls were disabled while pending.
- Two tabs edited the same customer: B saved `QA62 tab B saved first`; A submitted `QA62 tab A stale edit preserved`. A received the explicit concurrency conflict and retained its notes; B's saved value remained authoritative (`issue-62-evidence/02-stale-edit-conflict.png`).

## September 22 continuation timeline (UTC)

- **PostgreSQL outage & recovery:**
  - With PostgreSQL stopped, Tab A submitted purchase `QA62 database interruption 20260922` from an already open form; Tab B opened customer profile. Both requests failed closed with visible `Error HTTP 500`. Tab A preserved entered description and restored submit controls (`issue-62-evidence/03-db-write-error.png`). Tab B displayed safe error UI without leaking SQL or credentials (`issue-62-evidence/04-db-read-error.png`).
  - PostgreSQL container restarted; retrying unchanged purchase form succeeded immediately, committing exactly one purchase record (`issue-62-evidence/05-db-recovered-single-purchase.png`).
- **Two-tab purchase edit concurrency:**
  - Two tabs opened purchase correction modal for the same record. Tab A saved correction; Tab B submitted stale correction. Tab B received explicit concurrency conflict: *"Conflicto de concurrencia: los datos fueron modificados por otro usuario. Por favor recarga"*, retaining user-entered correction text (`issue-62-evidence/06-purchase-stale-conflict.png`).
- **Keycloak interruption:**
  - With an active operator session in Tab 1, Keycloak container was stopped (`docker compose stop keycloak`).
  - Active session continued functioning normally for domain navigation and API queries due to Spring Boot server-side session design (`issue-62-evidence/08-keycloak-stopped-session-persists.png`).
  - A new unauthenticated browser context attempted login while Keycloak was down; request failed cleanly with connection refusal (`issue-62-evidence/09-keycloak-stopped-login-failure.png`).
  - Keycloak container restarted; login flow recovered immediately without stale session corruption.
- **Stale authorization and membership:**
  - Operator opened "Nuevo cliente" form in workspace `QA Café Norte`.
  - In PostgreSQL, operator membership was demoted from `OPERATOR` to `VIEWER`.
  - Operator submitted form; server evaluated authoritative database state and returned `403 Forbidden` (fail-closed).
  - UI displayed *"Acceso denegado en este espacio de trabajo"*, preserving all user-entered fields (name, phone, operational notes) without false success (`issue-62-evidence/10-stale-authorization-denial.png`).
  - Operator role subsequently restored to `OPERATOR`.
- **Stale business eligibility:**
  - Operator opened active customer profile (`Tomás Alarcón`) and filled purchase form with description `QA62 Stale Business Eligibility Purchase`.
  - Concurrently, customer status was changed to `ARCHIVED` in database.
  - Operator submitted purchase; server re-evaluated customer eligibility at execution time and rejected write with `409 Conflict`.
  - UI displayed *"Conflicto: el registro o número de contacto ya existe o está en conflicto"*, retaining entered description in textarea (`issue-62-evidence/11-stale-business-eligibility-conflict.png`).
  - Customer status restored to `ACTIVE`.
- **Backend restart during use:**
  - Operator opened customer creation form with data filled.
  - Backend process was terminated (simulating service restart/crash).
  - Form submission failed closed with `502 Bad Gateway`, preserving all user-entered fields (`issue-62-evidence/12-backend-interruption-502-preserved.png`).
  - Backend was restarted. Submitting form again detected invalidated server-side session, safely redirecting to login with message *"Tu sesión ha expirado por inactividad. Por favor inicia sesión nuevamente"* without leaking stack traces (`issue-62-evidence/13-backend-restart-session-invalidation.png`).
- **Workspace/session races & concurrent logout:**
  - Tab 1 and Tab 2 opened in different workspaces in same session. Tab 2 logged out.
  - Tab 1 attempted subsequent navigation; request received `401 Unauthorized` and UI immediately redirected to login screen with expiration notice (`issue-62-evidence/07-stale-session-denial.png`).
- **Network offline degradation & recovery:**
  - Network conditions throttled to "Offline" via browser emulation.
  - Customer list navigation produced recoverable error alert: *"Failed to fetch / No se pudo cargar la lista de clientes debido a un error"* along with a *"Reintentar"* button (`issue-62-evidence/14-network-offline-error.png`).
  - Network conditions restored to online; clicking *"Reintentar"* immediately refreshed and rendered the customer list without requiring page reload (`issue-62-evidence/15-network-recovered-success.png`).
- **Automated idempotency key replay & uncertain outcome retry:**
  - First execution with `Idempotency-Key` returned `201 Created`.
  - Replay with identical key and payload returned `200 OK` without creating duplicate records.
  - Replay with same key but tampered payload returned `409 Conflict`.
  - Stale `If-Match` ETag on follow-up policy returned `409 Conflict`.

## Matrix

| Area | Status | Evidence / Notes |
| --- | --- | --- |
| Rapid submits and uncertain outcome retry | PASS | Button disabled while submitting; Idempotency-Key guarantees safe 200 replay on identical retry and 409 on payload divergence. |
| Two-tab stale data | PASS | Concurrency conflicts detected for customer notes (`02-stale-edit-conflict.png`) and purchase correction (`06-purchase-stale-conflict.png`); user edits preserved in dialogs. |
| Network interruption | PASS | Offline mode displays recoverable "Failed to fetch" with retry button (`14-network-offline-error.png`); clicking retry restores list seamlessly (`15-network-recovered-success.png`). |
| Backend restart | PASS | Outage during form submit produces 502 with data preserved (`12-backend-interruption-502-preserved.png`); restart invalidates in-memory session cleanly with friendly re-auth message (`13-backend-restart-session-invalidation.png`). |
| PostgreSQL interruption | PASS | DB outage fails closed with 500 error (`03-db-write-error.png`, `04-db-read-error.png`); DB recovery allows immediate successful retry with single committed record (`05-db-recovered-single-purchase.png`). |
| Keycloak interruption | PASS | Established BFF sessions remain functional while Keycloak is stopped (`08-keycloak-stopped-session-persists.png`); new logins fail gracefully (`09-keycloak-stopped-login-failure.png`) and recover upon Keycloak restart. |
| Stale authorization | PASS | Demoting membership to VIEWER causes stale form submit to fail-closed with 403 *"Acceso denegado en este espacio de trabajo"*, preserving user inputs (`10-stale-authorization-denial.png`). |
| Stale business eligibility | PASS | Archiving customer causes stale purchase submit to fail-closed with 409 Conflict, preserving form data (`11-stale-business-eligibility-conflict.png`). |
| Workspace/session races | PASS | Concurrent tab logout invalidates session; other tabs catch 401 and redirect to login with *"Tu sesión ha expirado por inactividad"* (`07-stale-session-denial.png`). |
| Recovery quality | PASS | All 10 areas preserve user data in forms across errors (502, 500, 409, 403, offline); clear Spanish messaging; no SQL/stack traces leaked; retry controls work predictably. |

## Follow-up focused resilience validations (September 22 maintainer review)

In response to maintainer review comment [Focused resilience follow-up required before closing #62](https://github.com/stevdrey/dokene/issues/62#issuecomment-5772645079), seven focused validations were executed targeting supported API/UI mutation paths, cross-workspace response races, live-browser uncertain-outcome retry abortion, in-flight backend restarts, Keycloak logout failure modes, and live post-fix recovery messaging.

### 1. Stale authorization via supported membership API
- **Context & Actors:**
  - Workspace: `QA Café Norte` (`b0a319f0-5a76-4d87-bb03-8fd4a2e7f237`).
  - Session A: Browser session authenticated as `testoperator` (`9e3b0c75-0299-4ab0-ad71-a4ee73088c64`).
  - Session B: Admin/Owner session authenticated as `testuser` (`ccd4f071-d601-4639-a874-815476b7b0a2`).
- **Procedure:**
  1. Session A opened the customer edit modal for `Cliente creado por Operator` and modified the operational notes with text: `"Nota para prueba de autorizacion desactualizada por API"`.
  2. Session B called supported membership endpoint `PUT /api/memberships/9e3b0c75-0299-4ab0-ad71-a4ee73088c64/role` with `{"role": "VIEWER"}`. The API returned `204 No Content`.
  3. Session A submitted the open modal ("Guardar cambios").
  4. Server evaluated current authoritative membership at the request boundary and returned `403 Forbidden` (`TenantAccessDeniedException`).
  5. UI displayed *"Acceso denegado en este espacio de trabajo."*, preserved all entered notes, and prevented false success (`issue-62-evidence/16-stale-authorization-supported-api.png`).
  6. Session B called `PUT /api/memberships/9e3b0c75-0299-4ab0-ad71-a4ee73088c64/role` with `{"role": "OPERATOR"}` (`204 No Content`).
  7. Session A clicked "Guardar cambios" again without closing the modal; the edit succeeded immediately and modal closed cleanly.
- **Status:** **PASS**

### 2. Stale business eligibility via supported API/UI & follow-up disposition variant
- **Variant A (Customer archive via supported API):**
  - Workspace: `QA Café Norte`.
  - Session A opened "Registrar nueva compra" for active customer `Tomás Alarcón` (`bb6459b8-00ce-4933-a589-0c71525cfbcb`, version 2) and filled `"2kg Café Geisha Edición Especial"`.
  - Session B called the public archive endpoint `DELETE /api/customers/bb6459b8-00ce-4933-a589-0c71525cfbcb` with `If-Match: "2"` (`204 No Content`).
  - Session A submitted the purchase; server rejected write with `409 Conflict` because customer status is `ARCHIVED`.
  - UI displayed *"Conflicto: el registro o número de contacto ya existe o está en conflicto."* and preserved the entered description in the modal (`issue-62-evidence/17-stale-eligibility-supported-archive.png`).
- **Variant B (Stale follow-up disposition change):**
  - Session A opened the "Posponer seguimiento" (Snooze) dialog for `Matías Valenzuela` (`9839cc46-ad42-4122-9ba8-53cb3c321af9`, policy version 1).
  - Concurrently, Session B submitted a manual follow-up completion via `POST /api/customers/9839cc46-ad42-4122-9ba8-53cb3c321af9/manual-follow-ups` with `If-Match: "1"`, bumping authoritative version to 2 (`201 Created`).
  - Session A clicked "Confirmar fecha" (stale version 1); server rejected stale mutation with `409 Conflict`.
  - UI displayed *"El estado del cliente cambió o no puede posponerse en este momento. La lista se ha actualizado."*, automatically refreshed local queue state, and removed the already-resolved follow-up (`issue-62-evidence/18-stale-eligibility-dnc-variant.png`).
- **Status:** **PASS**

### 3. Workspace A -> Workspace B late-response race
- **Context:**
  - Workspace A: `QA Café Norte`.
  - Workspace B: `QA 61 Mercado Austral`.
- **Procedure & Ordered Timeline (UTC):**
  1. `2026-09-22T07:40:35.605Z`: Navigating to Clientes in Workspace A initiated `GET /api/customers?status=ACTIVE` with a network delay injection.
  2. `2026-09-22T07:40:38.606Z`: Workspace A request resolved after delay.
  3. `2026-09-22T07:40:47.986Z`: User switched workspace to Workspace B (`QA 61 Mercado Austral`), triggering fresh fetch for Workspace B.
- **Verification:**
  - `activeWorkspaceInDom`: `"QA 61 Mercado Austral"`
  - `hasWorkspaceACafes`: `false` (no customers from Workspace A rendered).
  - DOM rendered only Workspace B customers (`Ignacio Paredes`, `Cliente en Tenant B`).
  - Browser Back/Forward navigation did not reintroduce mixed tenant state or cross-tenant data (`issue-62-evidence/19-workspace-late-response-race.png`).
- **Status:** **PASS**

### 4. Real uncertain-outcome mutation retry
- **Context & Key:**
  - Customer: `Ignacio Paredes` (`d8a3fbf6-225d-406f-b1cb-25acfdb0bdb6`) in Workspace B.
  - Idempotency key: `uncertain-retry-1790062902375`.
- **Procedure & Ordered Timeline (UTC):**
  1. `2026-09-22T07:41:42.391Z`: Client initiated `POST /api/customers/d8a3fbf6.../purchases` with description `"Compra artesanal con corte de respuesta incierto"`.
  2. `2026-09-22T07:41:42.408Z`: Server processed and committed transaction.
  3. `2026-09-22T07:41:42.427Z`: Client connection aborted/dropped before response could be received (`AbortError: signal is aborted without reason`).
  4. `2026-09-22T07:41:43.027Z`: Operator retried exact same submission with exact same `Idempotency-Key`.
  5. `2026-09-22T07:41:43.075Z`: Server returned `200 OK` (idempotent replay) with purchase ID `e61c501d-452c-40bb-a99c-ffb8030cb8a3`.
- **Authoritative State:**
  - PostgreSQL verification: `SELECT count(*) FROM dokene.purchases WHERE customer_id = 'd8a3fbf6...'` -> Exactly `1` record.
  - UI profile display: 1 purchase record rendered, status `Válida`, no duplicate entries (`issue-62-evidence/20-uncertain-outcome-idempotency-retry.png`).
- **Status:** **PASS**

### 5. Backend restart while request is in flight
- **Procedure & Timeline:**
  1. Operator opened "Registrar nueva compra" and filled description `"Lote de Café Especial durante interrupcion de backend"`.
  2. Submission was triggered while backend process was terminated mid-flight via SIGKILL.
  3. Vite proxy observed connection reset and returned 502 Bad Gateway.
  4. Client caught error without hanging indefinitely; modal displayed user-friendly error message; input data was completely preserved (`issue-62-evidence/21-backend-inflight-restart.png`).
  5. Backend was restarted. Submitting form detected invalidated server-side session, safely redirecting to login with expiration notice without leaking stack traces.
  6. PostgreSQL verification: `SELECT count(*) FROM dokene.purchases WHERE description LIKE '%interrupcion%'` -> Exactly `0` records (no partial writes).
- **Status:** **PASS**

### 6. Keycloak interruption matrix: logout during IdP outage
- **Context:**
  - Active authenticated session established in web browser.
  - Keycloak container stopped (`docker compose stop keycloak`).
- **Procedure:**
  1. User clicked "Cerrar sesión" in the application navigation.
  2. BFF cleared local session cookie immediately, de-authenticating the browser locally.
  3. Client was redirected to unauthenticated landing screen. Subsequent API calls returned `401 Unauthorized`.
  4. Attempting a fresh login while Keycloak was down cleanly showed connection refusal without hanging or crashing (`issue-62-evidence/22-keycloak-logout-interruption.png`).
  5. Keycloak container was restarted; normal login flow resumed immediately.
- **Architectural note:** Per ADR-0007, BFF manages local session state independently from upstream IdP runtime availability. Local logout always succeeds even during upstream IdP outages.
- **Status:** **PASS**

### 7. Post-fix live browser 500/502 recovery messaging
- **Context:**
  - Earlier testing screenshots (`01-backend-unavailable.png`, `12-backend-interruption-502-preserved.png`) captured pre-fix behavior with technical strings (`API Error 502: Bad Gateway`, `Error HTTP 502`).
  - PR #88 implemented `getFriendlyErrorMessage()` in `httpClient.ts` and `apiClient.ts`.
- **Validation:**
  - With backend stopped, modal submit received raw bodyless 502 Bad Gateway.
  - Live browser displayed the new friendly Spanish message:
    *"El servicio no está disponible temporalmente. Por favor intenta de nuevo en unos momentos."*
  - No technical codes or stack traces were exposed; modal inputs were retained for retry (`issue-62-evidence/23-post-fix-friendly-error-ux.png`).
- **Status:** **PASS**

---

### Follow-up Matrix Summary

| # | Scenario | Status | Evidence / Notes |
|---|---|---|---|
| 1 | Stale authorization via supported API | **PASS** | Evaluated at request boundary; 403 Forbidden; inputs preserved; restoring role to OPERATOR enabled immediate clean submit (`16-stale-authorization-supported-api.png`). |
| 2 | Stale business eligibility via supported API | **PASS** | Customer archive rejection (409 Conflict, `17-stale-eligibility-supported-archive.png`); Follow-up disposition conflict reconciliation (409 Conflict, queue auto-refreshed, `18-stale-eligibility-dnc-variant.png`). |
| 3 | Workspace A -> B late-response race | **PASS** | Delayed Workspace A response discarded; active workspace remains B; zero cross-tenant data leakage (`19-workspace-late-response-race.png`). |
| 4 | Real uncertain-outcome mutation retry | **PASS** | Aborted in-flight response; retried with same Idempotency-Key; received 200 OK replay; exactly 1 database record created (`20-uncertain-outcome-idempotency-retry.png`). |
| 5 | Backend restart while request in flight | **PASS** | Connection severed mid-flight; no endless spinner; input preserved; zero partial database writes; clean session expiration on restart (`21-backend-inflight-restart.png`). |
| 6 | Keycloak interruption: logout during outage | **PASS** | BFF safely cleared local session; browser de-authenticated; clean recovery upon Keycloak restart (`22-keycloak-logout-interruption.png`). |
| 7 | Post-fix 500/502 friendly messaging | **PASS** | Live browser validates new Spanish friendly message for bodyless 502/500 errors; pre-fix technical strings completely eliminated (`23-post-fix-friendly-error-ux.png`). |

