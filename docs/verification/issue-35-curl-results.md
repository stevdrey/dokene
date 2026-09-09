# Issue 35 HTTP verification

Date: 2026-09-08

## Environment

- Java: Temurin 26.0.2.1
- Docker Engine: 29.7.2
- Testcontainers: 2.0.5 with automatic Ryuk cleanup
- Database: temporary `postgres:17-alpine` container
- Application: Spring Boot 4.1.1 on a random local Tomcat port
- Client: the host `curl` executable invoked by `ProcessBuilder`
- Schema: Flyway migrations V1 through V10 applied successfully

All tenants, identities, customers, phone numbers, and UUIDs used by the test are synthetic and ephemeral.

## Reproduction

```shell
cd backend
./gradlew test --tests '*FollowUpCurlSystemTest' --no-daemon --console=plain
```

Final result: `BUILD SUCCESSFUL in 41s`; one system-test scenario passed. Testcontainers stopped the temporary
application resources and database after the JVM completed.

The final complete backend run used `./gradlew test --no-daemon --console=plain`. Its JUnit XML reports contain
331 tests with zero failures and zero errors. A subsequent `docker ps` check found no remaining
`postgres:17-alpine` Testcontainers container.

## Verified requests

| Scenario | Expected and observed |
| --- | --- |
| Create a tenant-scoped customer | `201 Created` |
| Read the initial tenant policy | `200 OK`, 30 days, `UTC` |
| Configure cadence and `America/Costa_Rica` | `200 OK` |
| Evaluate before consent | `200 OK`, `INELIGIBLE`, `NO_ELIGIBLE_CONTACT` |
| Grant WhatsApp consent and set today's explicit date | `200 OK`, then `DUE` and eligible |
| Snooze until tomorrow | `200 OK`, then `NOT_YET_DUE` and `SNOOZED` |
| Record a manual follow-up | `200 OK`; manual date stored and explicit/snooze dates cleared |
| Zero or 3651-day cadence | `400 Bad Request` |
| Unknown IANA time zone | `400 Bad Request` |
| Missing time zone | `400 Bad Request` |
| Malformed JSON | `400 Bad Request` |
| Past or malformed snooze date | `400 Bad Request` |
| Unknown customer | `404 Not Found` |
| Viewer reads customer policy | `200 OK` |
| Viewer attempts eligibility evaluation | `403 Forbidden` |
| Viewer attempts tenant-policy update | `403 Forbidden` |
| Owner of another tenant requests the customer | `404 Not Found` |
| Missing identity | `403 Forbidden` |
| Missing tenant context | `403 Forbidden` |
| Unrelated tenant without membership | `403 Forbidden` |

The executable evidence is preserved in
`backend/src/test/java/io/github/stevdrey/dokene/tenant/security/FollowUpCurlSystemTest.java`.
