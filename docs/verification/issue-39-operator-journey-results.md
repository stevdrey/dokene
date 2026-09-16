# Issue 39 Operator Journey & Workbench Verification

Date: 2026-09-15

## Environment

- Backend:
  - Java: Temurin 26.0.2.1
  - Docker Engine: 29.7.2
  - Testcontainers: 2.0.5 with automatic Ryuk cleanup
  - Database: temporary `postgres:17-alpine` container
  - Application: Spring Boot 4.1.1 on a random local Tomcat port
  - Client: host `curl` executable invoked by `ProcessBuilder` via `FollowUpOperatorJourneySystemTest`
  - Schema: Flyway migrations V1 through V11 applied successfully
- Frontend:
  - Node.js: v22+
  - Vitest: 4.0.8
  - Testing Library: React 16.3.2, user-event 14.6.1

All tenants, identities, customers, phone numbers, and UUIDs used during verification are synthetic and ephemeral.

## Reproduction

### Backend Automated Operator Journey System Test

```shell
cd backend
./gradlew test --tests '*FollowUpOperatorJourneySystemTest' --no-daemon --console=plain
./gradlew test --tests '*FollowUpCurlSystemTest' --no-daemon --console=plain
```

### Frontend Test Suite

```shell
cd frontend
npm test -- --run
```

## Verified Backend Operator Journey Scenarios

| Step / Scenario | HTTP Status & Response Verification |
| --- | --- |
| 1. Create primary tenant `Panadería El Trigal` (`POST /api/tenants`) | `201 Created`, tenant initialized with RLS policies |
| 2. Create customer `Valentina Morales Gómez` (`POST /api/customers`) with primary phone `+56984521190` | `201 Created`, version ETag `"0"` |
| 3. Grant WhatsApp consent (`PUT /api/customers/{id}/contacts/{id}/consents/WHATSAPP`) with source `CUSTOMER_WRITTEN` | `200 OK`, version ETag `"1"` |
| 4. Record initial purchase (`POST /api/customers/{id}/purchases`) with historical timestamp | `201 Created` |
| 5. Configure follow-up policy (`PUT /api/customers/{id}/follow-up-policy`) with cadence 14 days and `explicitNextDate = today` | `200 OK` |
| 6. Query operator due queue (`GET /api/follow-up-queue`) | `200 OK`, customer returned with status `DUE`, reason `DUE_TODAY`, primary phone `+56984521190` |
| 7. Execute dismissal disposition with notes & `Idempotency-Key` (`POST /api/customers/{id}/follow-up-dismissals`) | `201 Created`, customer drops from queue, cadence advanced |
| 8. Replay dismissal with same idempotency key | `200 OK`, returns original dismissal record (idempotency safety) |
| 9. Attempt dismissal on `NOT_YET_DUE` customer | `409 Conflict` |
| 10. Attempt snooze on `NOT_YET_DUE` customer | `409 Conflict` |
| 11. Reset customer policy to `explicitNextDate = today` | `200 OK`, customer reappears in due queue |
| 12. Execute snooze disposition until tomorrow (`PUT /api/customers/{id}/follow-up-snooze`) | `200 OK`, customer immediately drops from due queue |
| 13. Execute manual follow-up completion with notes & `Idempotency-Key` (`POST /api/customers/{id}/manual-follow-ups`) | `201 Created`, clears snooze, records `completedOn = today` |
| 14. Replay manual follow-up with same idempotency key | `200 OK`, returns original follow-up record |
| 15. Stale safeguard: Revoke WhatsApp consent (`PUT .../consents/WHATSAPP` -> `REVOKED`) | `200 OK` |
| 16. Attempt snooze on revoked consent customer | `409 Conflict` |
| 17. Attempt dismissal on revoked consent customer | `409 Conflict` |
| 18. Attempt manual follow-up on revoked consent customer | `409 Conflict` |
| 19. Stale safeguard: Soft-delete/archive customer (`DELETE /api/customers/{id}`) | `204 No Content` |
| 20. Attempt dispositions on archived customer | `409 Conflict` |
| 21. Cross-tenant isolation (`GET /api/follow-up-queue` from foreign tenant `Café Bellavista`) | `200 OK`, 0 items visible (RLS boundary enforced) |
| 22. Role gating: `VIEWER` reads queue (`GET /api/follow-up-queue`) | `200 OK` (has `FOLLOWUP_READ`) |
| 23. Role gating: `VIEWER` writes disposition (`PUT /api/customers/{id}/follow-up-snooze`) | `403 Forbidden` (lacks `FOLLOWUP_WRITE`) |

## Verified Frontend Workbench Scenarios

All 15 frontend test suites (155 tests) passed cleanly:

- `src/features/followups/__tests__/followUpApi.test.ts` (7/7 tests):
  - Fetches queue with status filtering.
  - Queries follow-up policies and eligibility.
  - Submits snooze with `If-Match`.
  - Submits dismiss with `If-Match` and `Idempotency-Key`.
  - Submits manual follow-up with `If-Match` and `Idempotency-Key`.
- `src/features/followups/__tests__/FollowUpWorkbench.test.tsx` (12/12 tests):
  - Renders workbench layout with header, filter pills, search input, and split panels.
  - Loads and displays queue items (`FollowUpCard`) with phone, status badge, and reason summary.
  - Filters cards in-memory by status (`TODOS`, `DUE`, `OVERDUE`) and search text.
  - Displays customer context detail including purchase history, consented channel, and reason breakdown.
  - Copies phone to clipboard with temporary feedback ("Copiado").
  - Navigates to customer profile when clicking customer link/button.
  - Opens and submits Manual Follow-Up modal with character counter, `If-Match`, and idempotency key.
  - Opens and submits Snooze modal with preset quick buttons and date picker.
  - Opens and submits Dismiss modal with notes.
  - Handles HTTP 409 Conflict with clear banner warning and queue refresh.
  - Enforces role gating: hides action buttons when user is `VIEWER`.
  - Responsive mobile drawer/view for small viewports.
- `src/App.test.tsx` (6/6 tests):
  - Renders top navigation with "Clientes", "Membresías", "Configuración", and "Seguimientos".
  - Displays `<FollowUpWorkbench>` when navigating to "Seguimientos" tab.
  - Seamlessly switches to "Clientes" tab and loads customer profile when requested from workbench.
