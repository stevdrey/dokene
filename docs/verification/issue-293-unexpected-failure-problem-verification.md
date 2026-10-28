# Issue #293 unexpected failures on Phase 3 routes: verification record

Status: single PR.
- **The problem:** an exception that no handler maps on `/api/messages/**`, `/api/templates/**` or `/api/integrations/**` used to leave through Spring's default error body, `{timestamp,status,error,path}`, which echoes the request path.
- **Now:** it answers the closed `500` problem `{type:"about:blank", title:"Internal Server Error", status:500, code:"INTERNAL_ERROR"}`.
- **Unchanged answers:**
  - database outages: the empty `503` with `Retry-After` (ADR 0029);
  - audit failures: the empty `503` (ADR 0006);
  - Spring MVC, servlet and Spring Security failures: their own statuses.
- **Design:** the ADR 0024 addendum for issue 293 and ADR 0023 §4.4.

## Environment

- **Run:** 2026-10-28, branch `293-phase-3-backend-an-unexpected-failure-on-a-phase-3-route-answers-500-with-the-default-error-body-instead-of-a-problem-body`, based on `f4cb0ec`.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build.
- **Frontend toolchain:** Node 24.
- **Database:** Testcontainers PostgreSQL 17 with the `dokene_runtime` and `dokene_migration` roles.
- No live provider call.

## Automated coverage

### `Phase3UnexpectedFailureIntegrationTest` (PostgreSQL, MockMvc with the real filter chain and `AuditRequestFilter`; 5 tests)

| Case | Test |
| --- | --- |
| **P3-KILL-07 shape.** At `POST /api/messages/{id}/sends`, the tenant outbound switch read fails with `PermissionDeniedDataAccessException` (SQLState `42501`, a canary message), as when the runtime role loses `SELECT` on `tenant_messaging_integrations`. The answer is `500` `application/problem+json` with exactly `type`, `title`, `status` and `code` = `INTERNAL_ERROR`. The body has no `path`, no `timestamp`, no message id and no canary. The message stays `APPROVED` at the same version, and no attempt row is written. | `anUnreadableOutboundSwitchAtSendIsAClosedInternalErrorWithNoEffect` |
| `GET /api/messages/{id}`, `GET /api/templates` and `GET /api/integrations/whatsapp` each answer the same closed problem when their service throws an unexpected exception carrying a canary. | `everyPhase3RouteFamilyAnswersTheSameClosedProblem` |
| A `CannotGetJdbcConnectionException` on a message route is still the empty `503` with `Retry-After` from the ADR 0029 boundary. | `aDatabaseOutageStillAnswersTheEmptyRetryable503` |
| An `AuditPersistenceException` on a template route is still the empty `503`. The audit handler is ordered first. | `anAuditFailureStillAnswersTheEmpty503` |
| `DELETE /api/messages/{id}` (no such route) is still `405`, and a malformed id is still the mapped `400 INVALID_INPUT`. | `springMvcErrorsKeepTheirOwnStatus` |

### Unit and contract

- `UnexpectedFailuresTest`:
  - ordinary runtime and data-access failures are unexpected;
  - `ErrorResponse`, `ServletException`, `AccessDeniedException` and `AuthenticationException` are not;
  - logging survives a cyclic cause chain.
- `MessagingContractFixturesTest` checks that `enums.json` and `problems.json` (now with `INTERNAL_ERROR`) match `MessagingErrorCode` and the problem shape. `TemplateErrorCodeParityTest` pins the new template code's name to the messaging one.
- `ModuleDependencyArchitectureTest` confirms that the new `http.UnexpectedFailures` depends on no module.
- Frontend: `messageErrors.test.ts` checks that a coded `500 INTERNAL_ERROR` is classified `uncertain`, like an uncoded `5xx`, so the review component reads the message again. `contractFixtures.test.ts` covers the fixture and union parity.

## Results

| Command (working directory) | Result |
| --- | --- |
| `./gradlew test --tests '*Phase3UnexpectedFailure*' --tests '*UnexpectedFailuresTest' --tests '*MessagingContractFixtures*' --tests '*WhatsAppIntegrationControllerTest' --tests '*DatabaseOutageIntegrationTest' --tests '*ModuleDependencyArchitectureTest' --tests '*TemplateErrorCode*'` (`backend/`) | **Passed**: 5, 3, 61, 27, 3, 21 and 1 tests |
| `./gradlew check` (`backend/`) | **Passed**: 188 suites, 2303 tests, 0 failures, 0 errors, 0 skipped |
| `npm test` (`frontend/`) | **Passed**: 46 files, 1104 tests |
| `npm run build` (`frontend/`) | **Passed** |

## Not covered here

- **The real-stack retest of P3-KILL-07** (revoke `SELECT` on `tenant_messaging_integrations` from the runtime role, then send):
  - the harness block that drives it belongs to the #149 acceptance run (PR #294) and is not on `main` yet, where P3-KILL-07 is still `BLOCKED`;
  - the integration test above drives the same failure through the real filter chain and handlers;
  - the real-stack retest belongs with the #149 harness once #294 and this PR are merged.
