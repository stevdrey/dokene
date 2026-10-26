# Issue #289 live WhatsApp status metadata: verification record

Status: single PR. It extends the status allowlist of `MetaWebhookPayloadParser` with the three members live Meta callbacks carry (`recipient_logical_id`, `recipient_user_id`, `internal_1p_only_data`). The policy is in the [ADR 0030](../adr/0030-verified-webhook-inbox-and-delivery-reconciliation.md) issue 289 addendum.

## Environment

- **Run:** 2026-10-24, branch `289-bug-strict-whatsapp-webhook-allowlist-rejects-live-meta-status-metadata-and-leaves-delivered-messages-sent`, based on `8cd5625`.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build.
- **Database:** Testcontainers PostgreSQL, as in the [issue 139 record](issue-139-webhook-inbox-verification.md).
- **No live Meta call.**
  - Callbacks are synthetic bodies in the observed live shape, built by `MetaCallbacks.liveReport` and signed with the fixture key `SYNTHETIC-APP-SECRET-NOT-REAL-0001` through `WebhookSigning`.
  - Every value is synthetic. No raw provider payload, recipient identifier or credential was copied from the live diagnosis.

## Automated coverage

| Acceptance item | Test |
| --- | --- |
| A signed batch in the live shape (extra recipient ids, `conversation`, `pricing`, `internal_1p_only_data`, value-level `contacts`) parses for `sent`, `delivered`, `read` and `failed` (code 131026 → `INVALID_RECIPIENT`). This batch was refused with `UNEXPECTED_MEMBER` before the fix | `MetaWebhookPayloadParserTest.theLiveStatusShapeParsesForEveryDeliveryStateAndNoExtraMemberSurvives` |
| None of the extra members, recipient phones or error texts survive parsing | same |
| Required delivery facts stay required: a live-shaped status without `id`, `status` or an epoch-second `timestamp` is `SCHEMA` | `MetaWebhookPayloadParserTest.aLiveShapedStatusWithoutItsRequiredFactsIsStillRefused` |
| P3-HOOK-04 still holds: a `tenantId` beside the new members is `UNEXPECTED_MEMBER`, as at every other level | `MetaWebhookPayloadParserTest.aMemberOutsideTheAllowlistAtAnyLevelRefusesTheWholeBody` |
| Signed live-shaped `delivered` and `read` callbacks over HTTP get `200` with an empty body, are durably `APPLIED`, move only the mapped tenant's message to `READ` with `deliveredAt`/`readAt`, and a replay is deduplicated | `WhatsAppWebhookIntegrationTest.liveShapedDeliveryAndReadCallbacksAreAppliedToTheMappedTenantOnly` |
| After one real send through the authenticated API, live-shaped callbacks advance the existing message to `READ`, the history gains two `DELIVERY_UPDATED` events, nothing of the extra metadata is shown, and Meta saw exactly one send | `ApprovedSendJourneyIntegrationTest.liveShapedDeliveryAndReadCallbacksAdvanceTheSentMessageWithoutResending` |
| The signed contract fixture `liveDelivered` (`contracts/whatsapp/graph-webhook-events.json`) carries the live shape, value-level `contacts` included. Its signature recomputes, its shape and synthetic values are pinned, and it parses to `DELIVERED` with nothing counted as unsupported | `WhatsAppProviderFixturesTest` (webhook entries, relations, shape) and `MetaWebhookPayloadParserTest.everySignedFixtureParsesToItsExpectedTypedStatus` |
| Live-shaped `sent` for an already `SENT` message is `200` and recorded `IGNORED`. Live-shaped `failed` (131026) is `200`, `APPLIED` and ends in `FAILED`/`INVALID_RECIPIENT`, with no canary or extra metadata in the history route | `WhatsAppWebhookIntegrationTest.liveShapedSentAndFailedCallbacksAreRecordedWithoutLeakingProviderText` |
| `deliveredAt` and `readAt` come from their own callbacks: the read is strictly later than the delivery and no later than the time after posting | `ApprovedSendJourneyIntegrationTest.liveShapedDeliveryAndReadCallbacksAdvanceTheSentMessageWithoutResending` |
| An `UNEXPECTED_MEMBER` refusal names its level at all six levels, and keeps a member name only when it matches `^[a-z0-9_]{1,64}$`; `tenantId`, a newline, a 65-character name and a dash are `<other>`. No value and no cause are carried | `MetaWebhookPayloadParserTest.aMemberOutsideTheAllowlistAtAnyLevelRefusesTheWholeBody` and `anUnexpectedMemberRefusalNamesItsLevelAndOnlyAMetaStyleKeyName` |
| Value-level `contacts` is type-checked but no longer counted as `UNSUPPORTED_EVENT`; inbound messages, value-level errors, other change fields and untracked statuses still are | `MetaWebhookPayloadParserTest.otherFieldsInboundMessagesAndUntrackedStatusesAreCountedNotProcessed` and `theLiveStatusShapeParsesForEveryDeliveryStateAndNoExtraMemberSurvives` |

The issue 139 record keeps its original count of five fixtures. It describes that run; the sixth fixture comes from this issue.

Unchanged negative coverage (issue 139 record): invalid signatures, malformed bodies and duplicate keys, tenant spoofing, unknown and unverified numbers, cross-tenant provider message ids, replays and reordering.

## Not covered here

- **Live re-test.** The issue's final item, an explicitly authorized send to the controlled recipient on the QA stack, is a manual step after merge. Existing `SENT` messages from the diagnosis are not marked delivered by hand and are not resent.
