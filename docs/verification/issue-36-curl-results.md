# Issue 36 HTTP Verification

Date: 2026-09-11

## Environment

- Java: Temurin 26.0.2.1
- Docker Engine: 29.7.2
- Testcontainers: 2.0.5 with automatic Ryuk cleanup
- Database: temporary `postgres:17-alpine` container
- Application: Spring Boot 4.1.1 on a random local Tomcat port
- Client: the host `curl` executable invoked by `ProcessBuilder`
- Schema: Flyway migrations V1 through V11 applied successfully

All tenants, identities, customers, phone numbers, and UUIDs used by the test are synthetic and ephemeral.

## Reproduction

```shell
cd backend
./gradlew test --tests '*FollowUpCurlSystemTest' --no-daemon --console=plain
./gradlew test --tests '*FollowUpManagementIntegrationTest' --no-daemon --console=plain
./gradlew check
```

## Verified Requests

| Scenario | Expected and Observed |
| --- | --- |
| Query follow-up queue for due items (`GET /api/follow-up-queue`) | `200 OK`, returns due customer item with `dueStatus: "DUE"`, effective cadence, dates |
| Query follow-up queue with `status=OVERDUE` | `200 OK`, empty items list (no overdue customers seeded) |
| Query follow-up queue with `status=DUE` | `200 OK`, returns due customer item |
| Dismissal without required `If-Match` or body | `400 Bad Request` |
| Dismissal with malformed ETag (`"02"`) | `400 Bad Request` |
| Dismissal with invalid idempotency key (`invalid key`) | `400 Bad Request` |
| First dismissal with `If-Match`, `Idempotency-Key`, and optional notes | `201 Created`, returns dismissal ID, `dismissedOn`, notes |
| Idempotent dismissal replay with stale `If-Match` and same key | `200 OK`, returns existing dismissal ID and original notes |
| Follow-up queue after dismissal | `200 OK`, customer excluded from queue as cadence advanced |
| Dismissal on customer who is `NOT_YET_DUE` | `409 Conflict` |
| Snooze on customer who is `NOT_YET_DUE` | `409 Conflict` |
| Snooze until tomorrow on due customer | `200 OK`, customer drops from queue |
| Manual follow-up with `If-Match`, `Idempotency-Key`, and notes | `201 Created`, clears snooze, returns notes |
| Idempotent manual follow-up replay | `200 OK`, returns original completion and notes |
| Revoke WhatsApp consent on customer | `200 OK`, consent status set to `REVOKED` |
| Snooze on customer with revoked consent | `409 Conflict` (eligibility safeguard) |
| Dismissal on customer with revoked consent | `409 Conflict` (eligibility safeguard) |
| Manual follow-up on customer with revoked consent | `409 Conflict` (eligibility safeguard) |
| Viewer role access to `GET /api/follow-up-queue` | `200 OK` (viewer has `FOLLOWUP_READ`) |
| Foreign tenant access to `GET /api/follow-up-queue` | `200 OK`, empty list (strict tenant RLS isolation) |
| Unauthorized access to `GET /api/follow-up-queue` | `403 Forbidden` |
