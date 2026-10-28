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
| On `GET /api/integrations/whatsapp`, an outage is still the empty `503` with `Retry-After`, and an audit failure is still the empty `503`. | `theIntegrationRoutesPassOutagesAndAuditFailuresThroughToo` |
| An `AuditPersistenceException` wrapped as the cause of another exception is still the empty audit `503`. | `anAuditFailureWrappedAsACauseIsStillTheAuditAnswer` |

### `Phase3ErrorPageSystemTest` (real server on a random port, JDK `HttpClient`; 3 tests)

A test-only filter fails marked requests before Spring Security and every controller, carrying a canary.

| Case | Test |
| --- | --- |
| `GET /api/messages/{id}` answers `500` `application/problem+json` with the four members and `INTERNAL_ERROR`. The body has no `path`, `timestamp`, message id or canary, and the policy headers match those of Spring Boot's own error page. | `aFilterFailureOnAPhase3RouteIsTheClosedProblem` |
| `/api/templates`, `/api/integrations/whatsapp` and `/api/customers/{id}/messages` answer the same problem. | `theTemplateIntegrationAndSubmitRoutesAreCoveredToo` |
| `/api/customers` (Phase 1) keeps Spring Boot's default error body. | `otherRoutesKeepSpringBootsErrorBody` |

### Unit and contract

- `Phase3ErrorPageFilterTest` checks that every Phase 3 path, including the submit route under a customer, is matched, and that no Phase 1 or 2 path or the webhook is.
- `UnexpectedFailuresTest`:
  - type-mismatch, bind, unwritable-response and client-abort failures are not claimed, and neither is a committed response or an outage;
  - the log line carries classes and stack frames but no message canary;
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
| `./gradlew test --tests '*Phase3UnexpectedFailure*' --tests '*Phase3ErrorPage*' --tests '*UnexpectedFailuresTest' --tests '*ModuleDependencyArchitectureTest' --tests '*DatabaseOutageIntegrationTest' --tests '*WhatsAppIntegrationControllerTest'` (`backend/`, after the code review fixes) | **Passed**: 7, 3 + 2, 4, 21, 3 and 27 tests. The first run of `Phase3ErrorPageSystemTest` failed one assertion that expected `X-Content-Type-Options` on the error dispatch. Spring Security's header writer, a once-per-request filter, does not run on that dispatch, so the test now asserts the same policy headers as Spring Boot's own error page. |
| `./gradlew check` (`backend/`, after the code review fixes) | **Passed**: 190 suites, 2311 tests, 0 failures, 0 errors, 0 skipped |
| `npm test` (`frontend/`) | **Passed**: 46 files, 1104 tests |
| `npm run build` (`frontend/`) | **Passed** |

## Not covered here

- **The real-stack retest of P3-KILL-07** (revoke `SELECT` on `tenant_messaging_integrations` from the runtime role, then send):
  - the harness block that drives it belongs to the #149 acceptance run (PR #294) and is not on `main` yet, where P3-KILL-07 is still `BLOCKED`;
  - the integration test above drives the same failure through the real filter chain and handlers;
  - the real-stack retest belongs with the #149 harness once #294 and this PR are merged.
