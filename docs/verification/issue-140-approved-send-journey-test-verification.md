# Issue #140 approved-send journey test: verification record

Status: first of the #140 PRs. It adds one deterministic backend test that drives the whole Phase 3 approved-send journey over the HTTP API. It does not compose the workbench UI, run the real stack, or make a live provider call; those remain open under #140.

## Environment

- **Run:** 2026-10-23, branch `140-approved-send-journey-test`, based on `fca5145`.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build.
- **Database:** Testcontainers PostgreSQL 17 (`postgres:17-alpine`), with the `dokene_runtime` and `dokene_migration` roles.
- **No live Meta or OpenAI call.**
  - Meta is a loopback JDK `HttpServer`, set through `dokene.integration.whatsapp.graph.base-url`. It answers in the shape of `contracts/whatsapp` and counts every send request per phone number id.
  - Each workspace has its own business account id, bound to its phone number id and its tenant token. The phone-number and template reads answer only for the business account in the path, and only to that tenant's `Bearer` token; anything else gets a Graph `401`. Every read is recorded, so the test asserts which account and credential each read used.
  - Tenant access tokens are synthetic values (`SYNTHETIC-NOT-A-REAL-TOKEN-<tenant>`) from the local secret source, keyed by pre-registered tenant ids.
  - Callbacks are signed with the fixture key `SYNTHETIC-APP-SECRET-NOT-REAL-0001` through `WebhookSigning`.
  - The AI provider is the deterministic fake (`dokene.ai.provider=fake`).

## What runs for real

Unlike the other messaging integration tests, this class imports neither `MappedGeneralFollowUpGateConfiguration` nor `ScriptedMessagingProvider`. The following run as in production:
- the registry-backed template gate, with approval verified through `approval-refresh`;
- `WhatsAppOutboundSafetyControl`, with the global switch on and the tenant switch flipped through its route;
- local secret resolution;
- `GraphApiPhoneNumberOwnershipVerifier`, `GraphApiTemplateProviderGateway` and `GraphApiMessagingProvider`;
- the webhook signature verifier, the inbox and delivery reconciliation. The scheduled sweeper is off (`webhook.inbox.enabled=false`), so each callback must be applied by the inline step of its own request; the test asserts the inbox row is `APPLIED`.

Callback bodies come from the shared `integration/testsupport/MetaCallbacks` helper, which `WhatsAppWebhookIntegrationTest` uses too.

Every step goes through MockMvc with the real filter chains and CSRF: integration setup, cadence, customer, consent, purchase, due date, queue, draft, submit, approval, send, callback, detail and events.

## Automated coverage

### `ApprovedSendJourneyIntegrationTest` (5 tests)

| Acceptance item or case | Test |
| --- | --- |
| Full journey: synthetic customer → consent → purchase → due in the queue → AI draft → `AI_DRAFT` submit → explicit approval → manual send → provider acceptance → signed `delivered` callback → `DELIVERED`, with the full event timeline. No generated draft reaches Meta before approval, and a send before approval is `INVALID_TRANSITION`. The one Graph request carries the tenant's own bearer token, the approved recipient and the approved body. P3-DUP-01: a double click with the same key and `If-Match` replays the result; a later click with a new key is refused; still one Graph request. P3-HOOK-06: the duplicate callback changes nothing | `anAiDraftIsSentOnceOnlyAfterExplicitApprovalAndTrackedToDelivery` |
| A signed `failed` report (Meta code 131026) ends in `FAILED` with `INVALID_RECIPIENT`; the provider's error text never reaches the message detail, the event timeline or the message list | `aFailedDeliveryReportEndsInFailedWithASafeCategory` |
| Uncertain outcome: a Graph `5xx` leaves the message `SENDING` with `outcomeUnknown` and a `SEND_OUTCOME_UNKNOWN` event; a later send is refused and Meta sees one request only | `anUncertainProviderAnswerIsKeptUncertainAndNeverResent` |
| Zero provider requests after approval for: P3-PERM-06 (viewer send, `403`), P3-KILL-02 (tenant switch off, `OUTBOUND_DISABLED_FOR_TENANT`), the integration disabled through `PUT /api/integrations/whatsapp` (`INTEGRATION_DISABLED`), then re-enabled without a new ownership proof (still `INTEGRATION_DISABLED`, `webhookVerified` false until proven again), P3-CONS-04 (consent revoked, `NO_CONTACT_CONSENT`), and re-granted consent (`APPROVAL_INVALIDATED`). The message stays `APPROVED` with no attempt | `refusalsAfterApprovalReachMetaZeroTimes` |
| Drift: P3-APPR-13 (template remapped and re-verified after approval, `APPROVAL_INVALIDATED`, zero Graph requests). P3-APPR-07 (submit against an older policy version, `STALE_VERSION`) | `aTemplateEditAfterApprovalIsDriftAndAStaleDraftIsNotSubmitted` |

The global switch off (P3-KILL-01/03) needs its own Spring context and stays covered by `OutboundKillSwitchIntegrationTest`.

## Results

| Command (working directory) | Result |
| --- | --- |
| `./gradlew test --tests '*ApprovedSendJourneyIntegrationTest' --tests '*WhatsAppWebhookIntegrationTest' --tests '*WhatsAppProviderFixturesTest'` (`backend/`) | **Passed** (after the code review fixes): 5, 10 and 52 tests |
| `./gradlew check` (`backend/`) | **Passed** (after the code review fixes): 185 suites, 2278 tests, 0 failures, 0 errors, 0 skipped |

The first targeted run had one failure, and the bug was in the test: the duplicate-click replay sent the new ETag. A real double click repeats the original `If-Match`, so the test now does too, and the server answers that with a `200` replay.

A code review then tightened the test: strict business-account and token checks in the Graph stand-in, the sweeper turned off with an `APPLIED` inbox assertion, field-presence checks for `deliveredAt` and `attempts`, the leak check extended to the timeline and the list, and the shared callback builder. A follow-up P2 review added the integration disable and re-enable steps through the real route, so "disable yields zero provider requests" is proven at this level too.

## Not covered here

- Workbench composition and the frontend journey tests (#140, after #147 and #148).
- Real-stack browser and API scenarios, Phase 1/2 regression on one SHA, and the #149 security report.
- The opt-in live Meta send and callback, and the live OpenAI draft. No live-provider claim is made by this record.
