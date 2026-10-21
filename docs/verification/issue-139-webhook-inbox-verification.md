# Issue #139 webhook inbox and delivery tracking: verification record

Status: single PR. It delivers the `/webhooks/whatsapp` route, the strict parser, the verified-mapping routing gate, the durable inbox (migration V29), the processor and sweeper, and retention. The design is in [ADR 0030](../adr/0030-verified-webhook-inbox-and-delivery-reconciliation.md).

## Environment

- **Run:** 2026-10-21, branch `139-phase-3-backend-persist-verified-webhook-inbox-and-deliveryfailure-tracking`, based on `5f5d5b0`.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build.
- **Database:** Testcontainers PostgreSQL 17 (`postgres:17-alpine`), with the `dokene_runtime` and `dokene_migration` roles (`NOBYPASSRLS`).
- **No live Meta call.**
  - Callbacks are synthetic bodies signed with the fixture key `SYNTHETIC-APP-SECRET-NOT-REAL-0001`, through `WebhookSigning`.
  - The app secret and verify token come from the local secret source.
  - Live callback evidence belongs to the final integration run (#130).

## Automated coverage

### `WhatsAppWebhookIntegrationTest` (PostgreSQL, MockMvc with the real filter chains)

| Acceptance item or case | Test |
| --- | --- |
| P3-HOOK-15: the tenant comes from the verified mapping and only that tenant's message moves. P3-HOOK-06: a replay changes nothing and writes no second audit row. History shows `DELIVERY_UPDATED` / `PROVIDER` with no raw content | `aSignedDeliveryMovesOnlyTheMappedTenantsMessageOnceAndAReplayChangesNothing` |
| P3-HOOK-05: another tenant's provider message id is parked, then `UNMATCHED` with the id cleared; the other tenant is untouched | `anotherTenantsProviderMessageIdIsParkedThenDroppedWithoutTouchingIt` |
| P3-HOOK-08 and the #210 gate: tenant B pre-registers tenant A's phone id, and a signed event for that number is never processed in B's context; unknown and unverified-only numbers get `200` and store nothing | `unknownAndUnverifiedNumbersAreAcknowledgedButNeverRouted` |
| P3-HOOK-04: a supplied `tenantId` member gets `400` before tenant resolution, and nothing is stored | `aMemberOutsideTheProviderSchemaIsRefusedBeforeAnyTenantIsResolved` |
| P3-HOOK-11, P3-CSRF-04/05, and the body limit: a session cookie without a signature gets `403`; no CSRF token is needed on the webhook while `/api` still requires one; there is no `Set-Cookie` and no redirect; `/webhooks/other` gets `403`; an oversized body gets `403`; and the handshake answers right and wrong tokens | `onlyTheSignatureAuthenticatesTheRouteWhichAloneSkipsCsrfAndBoundsItsBody` |
| A reordered multi-status batch: only forward transitions apply; a late failure keeps the delivery; an untracked status is only counted | `aReorderedMultiStatusBatchAppliesOnlyForwardTransitionsAndALateFailureKeepsTheDelivery` |
| The T8 race and restart: a report that arrives before the provider id is committed stays `PENDING`, and the sweeper applies it afterwards | `aReportArrivingBeforeTheProviderIdIsCommittedIsAppliedOnceItIs` |
| Audit outage: the event is still acknowledged with `200`, the row stays `PENDING` and the message is unchanged; after recovery the sweeper applies it with exactly one audit row | `anAuditOutageKeepsTheAcknowledgedEventPendingUntilItCanBeApplied` |
| P3-RLS-04 for `provider_webhook_events` and retention: no rows are visible without context or from another tenant, RLS is forced, and rows are purged after 31 days | `inboxRowsAreTenantIsolatedAndPurgedAfterRetention` |

### `MetaWebhookPayloadParserTest` (unit)

- All five signed fixtures of `contracts/whatsapp/graph-webhook-events.json` parse to their expected typed statuses, including `failed` → `INVALID_RECIPIENT`.
- The canaries in error texts, recipients and conversations never survive parsing.
- An unknown member at each allowlisted level is refused.
- Malformed or mistyped bodies and duplicate keys are refused.
- Unsupported fields and statuses are counted.
- A failure without a usable code is `UNKNOWN`.
- More than 1000 statuses are refused.

### Schema, architecture and telemetry

- `TenantPersistenceIntegrationTest` pins migration version 29 and the table list.
- `TenantIsolationSecurityIntegrationTest` pins forced RLS, the five policies and the `SELECT, INSERT, UPDATE` grant on `provider_webhook_events`.
- `ModuleDependencyArchitectureTest.webhooksNeverReachMessageCommandsOrTheSendPath` proves that no webhook can create, approve or send.
- `WebhookTelemetryTest.theDroppedCounterIsSeparateFromRefusalsAndTaggedOnlyWithTheReason` covers the dropped counter.
- `RequiredPermissionCoverageTest` lists the webhook `receive` method as a justified signature-authenticated exception.
- `GraphApiSendErrorsTest` is unchanged and passes after `categoryFor` was extracted, so the send mapping did not regress.

## Results

| Command (working directory) | Result |
| --- | --- |
| `./gradlew check` (`backend/`) | **Passed**: 178 suites, 2193 tests, 0 failures, 0 errors, 0 skipped. Per-test listing for the relevant suites: [issue-139-evidence/test-results.txt](issue-139-evidence/test-results.txt) |
| `npx tsc -b` (`frontend/`) | **Passed** after the Vite `/webhooks` proxy entry |
| `npx vitest run src/securityHeaders.test.ts` (`frontend/`) | **Passed**: 3 tests; the nginx header snippets are unchanged |

The first full `check` run failed with 91 failures from two causes, and both were fixed before the run recorded above:
- The webhook `SecurityFilterChain` was created in non-web test contexts (`spring.main.web-application-type=none`); it is now `@ConditionalOnWebApplication`, as the BFF chain is.
- `RequiredPermissionCoverageTest` flagged the unauthenticated `receive` handler; it is now a documented exception.

## Not covered here

- **P3-APPR-12** (an operator holding an old ETag after a webhook transition) remains Preparation.
- **The nginx path handling of P3-CSRF-05** needs the real stack.
- **Live Meta callbacks** belong to the final integration run.
