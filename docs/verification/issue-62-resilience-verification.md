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
