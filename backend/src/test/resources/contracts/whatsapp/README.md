# WhatsApp Graph API fixtures

Synthetic Meta Cloud API traffic for the WhatsApp provider boundary, on both tracks:

- `graph-send-responses.json`: answers to `POST /{phone-number-id}/messages` ([ADR 0027](../../../../../../docs/adr/0027-meta-whatsapp-send-adapter.md)), replayed by `GraphApiMessagingProviderTest` from a loopback server. Each entry is `{ "status": <HTTP status>, "body": <JSON body> }`.
- `graph-webhook-events.json`: signed statuses callbacks for the webhook inbox ([ADR 0024](../../../../../../docs/adr/0024-phase-3-messaging-contract-freeze.md) raw bytes rule, spec 0007 `WebhookSigning`). Each entry is `{ "signature": <X-Hub-Signature-256 value>, "body": <the exact callback bytes as one JSON string>, "expected": <the typed outcome> }`.

`WhatsAppProviderFixturesTest` proves both files agree with themselves, with the domain types, and with the tables below. It recomputes every signature, checks every entry shape, and fails when a fixture id is missing from these tables or a table row has no fixture. To change a webhook body, edit the string, set its `signature` to `sha256=TODO`, run the test and paste the value the failure prints.

Rules:

- Data is synthetic only: `+50600000nn` numbers, `wamid.SYNTHETIC-…` message ids, `SYNTHETIC-NOT-A-REAL-TOKEN` credentials, `SYNTHETIC-TRACE` trace ids, the `SYNTHETIC-…` conversation id, the `CANARY-TOKEN`, `CANARY-PHONE` and `CANARY-BODY` literals, and the signing key `SYNTHETIC-APP-SECRET-NOT-REAL-0001`. Numeric Meta identifiers use the `1000000000000nn` (phone number id), `2000000000000nn` (business account) and `50600000nn` forms. Never copy a real provider response here.
- `signingKey` is a published test only value, not a secret. A deployment never configures it; the verifier resolves its real app secret from the secret source, so these fixtures only verify against a stack whose app secret was deliberately set to the synthetic key.
- The three fixed canaries live in `SecretCanaries.TOKEN`, `PHONE` and `BODY` (`SecretCanaries.fixedNeedles()`), because checked in bytes cannot carry the random per run `SecretCanaries.value()`. Tests search results and logs for them to prove redaction.
- `tokenExpiredWithSecrets` deliberately echoes a token, a phone number and message text, and `tokenExpiredWithCanaries` echoes the three canaries, so the redaction test can prove none of them crosses the adapter.
- `duplicateOfDelivered` is byte identical to `delivered`, signature included, on purpose: whatever key the inbox derives for deduplication, these two collide.
- The ordering pair `readBeforeDelivered` and `deliveredAfterRead` shares one message id and reverses the timestamps for realism. Today's `OutboundMessage` ignores the late `delivered` report because the message is already `READ`, whatever its timestamp says; `boundReportTime` would clamp a past report anyway.
- These fixtures describe provider behaviour, not Dokene's HTTP contract; changing them does not version `contracts/messaging`.

## Send fixtures

The typed outcome is the `ProviderSendResult` the adapter must return; the source test is the one in `GraphApiMessagingProviderTest` that asserts it.

| Fixture id | Typed outcome | Source test |
|---|---|---|
| `accepted` | `Accepted("wamid.SYNTHETIC-0001")` | `postsTheApprovedTemplateWithBoundParametersInOrderAndTheTenantCredentialAsBearerOnly` |
| `acceptedWithoutStatus` | `Accepted("wamid.SYNTHETIC-0002")` | `omitsTheBodyComponentForATemplateWithoutParameters` |
| `heldForQualityAssessment` | `Accepted("wamid.SYNTHETIC-0003")` | `heldForQualityAssessmentIsAcceptanceButAPausedOrIdlessAnswerIsUncertain` |
| `paused` | `OutcomeUnknown(Optional.of("wamid.SYNTHETIC-0004"))` | same |
| `acceptedWithoutId` | `OutcomeUnknown(Optional.empty())` | same |
| `acceptedWithUnsafeId` | `OutcomeUnknown(Optional.empty())` | same |
| `templateParameterMismatch` | `RejectedPermanently(TEMPLATE_REJECTED)` | `definitiveRefusalsArePermanentWithANormalizedCategory` |
| `templateMissing` | `RejectedPermanently(TEMPLATE_REJECTED)` | same |
| `invalidParameter` | `RejectedPermanently(TEMPLATE_REJECTED)` | same |
| `recipientUndeliverable` | `RejectedPermanently(INVALID_RECIPIENT)` | same |
| `accountRestricted` | `RejectedPermanently(POLICY_VIOLATION)` | same |
| `tokenExpiredWithSecrets` | `RejectedPermanently(AUTHENTICATION)` | same, and `noSecretPhoneOrMessageTextFromASecretBearingErrorReachesResultsOrLogs` |
| `tokenExpiredWithCanaries` | `RejectedPermanently(AUTHENTICATION)` | `noSecretPhoneOrMessageTextFromASecretBearingErrorReachesResultsOrLogs` |
| `permissionMissing` | `RejectedPermanently(AUTHENTICATION)` | `definitiveRefusalsArePermanentWithANormalizedCategory` |
| `throughputLimit` | `FailedTransiently(RATE_LIMITED)` | `refusalsThatProveNothingWasSentAreTransient` |
| `appRateLimit` | `FailedTransiently(RATE_LIMITED)` | same |
| `unknownErrorRefused` | `FailedTransiently(PROVIDER_UNAVAILABLE)` | same |
| `genericServerError` | `OutcomeUnknown(Optional.empty())` | `aServerErrorIsUncertainWhateverMetaCodeItCarriesSoItIsNeverResent` |
| `temporaryDowntime` | `OutcomeUnknown(Optional.empty())` | same |
| `serviceUnavailable` | `OutcomeUnknown(Optional.empty())` | same |
| `unknownServerError` | `OutcomeUnknown(Optional.empty())` | `anOpaqueServerErrorOrGatewayPageIsUncertainAndNeverRetried` |
| (code only) timeout after possible submission | `OutcomeUnknown(Optional.empty())`, never retried | `aTimeoutAfterSubmissionIsUncertainAndBounded` |

The timeout case has no fixture entry: a hang cannot be a replayed JSON body, so the test drives it in code.

## Webhook fixtures

All six callbacks are signed with `signingKey` over the exact `body` bytes. `statusBefore` is the `MessageStatus` the message is in when the entry is posted, `handling` is what the inbox is expected to do with it (`applied`, `deduplicated` or `ignoredTerminal`), `after` names the entry that must have been posted first, and the posting order groups the entries into their four scenarios. The expectations follow `OutboundMessage.applyDeliveryReport` as it is today; the `failed` category is `INVALID_RECIPIENT` because `GraphApiSendErrors` maps Meta code 131026 there on the send track, and the two tracks must not disagree about one code. Meta statuses carry no event id; the inbox derives `hex(SHA-256("META_WHATSAPP|" + tenantId + "|" + id + "|" + status))` (issue 139, ADR 0030 §3), and the duplicate is built so that any derivation collides.

| Fixture id | Posting order | `statuses[0].id` | `status` | `occurredAt` | `statusBefore` | `failure` | `handling` | `after` |
|---|---|---|---|---|---|---|---|---|
| `delivered` | 1 | `wamid.SYNTHETIC-HOOK-0001` | `DELIVERED` | `2025-10-09T08:53:20Z` | `SENT` | `null` | `applied` | `null` |
| `duplicateOfDelivered` | 2 | same bytes as `delivered` | `DELIVERED` | same | `DELIVERED` | `null` | `deduplicated` | `delivered` |
| `failed` | 1 | `wamid.SYNTHETIC-HOOK-0002` | `FAILED` | `2025-10-09T08:55:00Z` | `SENT` | `INVALID_RECIPIENT` | `applied` | `null` |
| `readBeforeDelivered` | 1 | `wamid.SYNTHETIC-HOOK-0003` | `READ` | `2025-10-09T08:56:40Z` | `SENT` | `null` | `applied` | `null` |
| `deliveredAfterRead` | 2 | `wamid.SYNTHETIC-HOOK-0003` | `DELIVERED` | `2025-10-09T08:55:50Z` | `READ` | `null` | `ignoredTerminal` | `readBeforeDelivered` |
| `liveDelivered` | 1 | `wamid.SYNTHETIC-HOOK-0004` | `DELIVERED` | `2025-10-09T08:58:20Z` | `SENT` | `null` | `applied` | `null` |

The `failed` entry carries the three canaries in `errors[0].title` (`CANARY-TOKEN`), `errors[0].message` (`CANARY-PHONE`) and `errors[0].error_data.details` (`CANARY-BODY`); no other webhook body contains any of them. `delivered` and `duplicateOfDelivered` carry a realistic `conversation` object a parser must ignore. `liveDelivered` has the shape live Meta status callbacks carry (issue 289): value-level `contacts` and, on the status, `recipient_logical_id`, `recipient_user_id`, `conversation`, `pricing` and `internal_1p_only_data`. All of their values are `SYNTHETIC-…` literals, and the parser accepts and never reads them.

Security cases that post these fixtures (see `docs/verification/phase-3-security-cases.md`): P3-SEC-05 and P3-TMPL-06 use `tokenExpiredWithCanaries` and the webhook `failed` entry; P3-HOOK-02 posts `delivered` with a wrong key, and the `WebhookSigning.mutationsOf` variants for the tampered and whitespace posts.
