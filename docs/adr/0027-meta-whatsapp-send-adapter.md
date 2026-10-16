# ADR 0027: Meta WhatsApp Cloud API Send Adapter

## Status

Accepted. Implements the `MessagingProvider` port frozen by [ADR 0023](0023-phase-3-outbound-messaging-contracts.md) §1 and [ADR 0024](0024-phase-3-messaging-contract-freeze.md) §3. Issue: [#137](https://github.com/stevdrey/dokene/issues/137), part of epic [#130](https://github.com/stevdrey/dokene/issues/130). It changes no contract, migration, permission or state transition.

## Context

The dispatcher (#142) needs a transport that sends one already-approved WhatsApp template and reports what happened as a `ProviderSendResult`. The adapter has to send a side-effecting `POST` with a tenant bearer token to an external host. That makes three things safety-relevant: where the token goes, how much of the answer is read, and how an ambiguous answer is reported. A wrong "failed" invites a duplicate message. A wrong "accepted" marks a message sent that never left.

`integration.whatsapp` already holds the bounded Graph transport (`GraphApiHttp`) and the host allowlist (`GraphApiProperties`, ADR 0025 §2). The ownership verifier (ADR 0024, issue 210 addendum) and the template gateway (ADR 0025) use them.

## Decision

### 1. Placement and authority

`GraphApiMessagingProvider` (`integration.whatsapp`, a `@Component`) implements `MessagingProvider`. It is a transport and nothing more:

- It has no policy or approval authority. Approval, kill switches, frequency checks and the approval fingerprint are enforced by the dispatcher before the call (ADR 0023 §4.3).
- The adapter sends only what the `OutboundSendCommand` carries. It never accepts a URL, provider JSON or a credential reference from the command or a browser.
- No provider result changes business state directly. The dispatcher maps the returned `ProviderSendResult` through the aggregate's transitions.

### 2. Request

| Aspect | Rule |
| --- | --- |
| Endpoint | `POST {baseUrl}/{phoneNumberId}/messages` |
| API version | Graph API **v23.0** (`GraphApiProperties.DEFAULT_BASE_URL`). Meta's version table (checked 2026-10-15) lists v23.0 as available until **2027-10-08**. A later version needs a deliberate bump of the default and the adapter tests before that date. |
| Host allowlist | Reuses ADR 0025 §2: HTTPS `graph.facebook.com`, loopback test servers, or an exact host in `dokene.integration.whatsapp.graph.additional-trusted-hosts`. Startup fails on anything else. Redirects are never followed. |
| Sender | Taken from server-side state, never from the command. The adapter reads the current tenant from the trusted context (dispatch is synchronous in that context, ADR 0024 §4) and the tenant's `WhatsAppIntegration`. A missing, disabled or unverified integration is refused without a call. The command carries the `SenderRevision` the approval bound (integration id, phone number id, business account id, credential revision). If the current integration's revision (`WhatsAppOutboundSafetyControl.senderRevision`) differs, even after a fresh ownership proof, the send is refused with `RejectedPermanently(POLICY_VIOLATION)` and nothing is sent. |
| Credential | Resolved per send through `IntegrationSecretResolver` in `Tenant` scope, held in a `ResolvedSecret` for the call only, and sent only in the `Authorization: Bearer` header. |
| Body | Built by the adapter: `messaging_product=whatsapp`, `recipient_type=individual`, `to` (E.164), `type=template`, `template.name`, `template.language.code`, and one `body` component whose `text` parameters are exactly the command's bound parameters, in order. The component is omitted when there are no parameters. Free-form text is never sent (ADR 0025 §4). |
| Local validation | Before any call: E.164 recipient, `TemplateMapping.PROVIDER_TEMPLATE_NAME` and `LANGUAGE`, at most `TemplateMapping.MAX_PARAMETERS` parameters, and every value accepted by `TemplateParameterValues.acceptable`, the same rule approval binds: non-empty, at most 1024 Unicode code points, no run of five spaces, and no control, line or paragraph separator or disallowed format character. Sharing the rule means an approved message is never refused here for a reason approval did not apply. A failure is `RejectedPermanently` (`INVALID_RECIPIENT` or `TEMPLATE_REJECTED`) and nothing is sent. |
| Idempotency | Meta has no client idempotency token, so `sendKey` is not sent (ADR 0023 §4.2). Exactly-once rests on the dispatcher's persist-then-call discipline. |

### 3. Bounds

There is one deadline (`dokene.integration.whatsapp.graph.timeout`, default 5s) for connecting, sending, the headers and the body. Connecting gets at most half of it (the client's `connectTimeout`), so a connect that hangs fails as a connect timeout, which is provably not sent, before the request timeout, which cannot tell. Only if resolving the secret already used more than half the budget can a hanging connect still end as `OutcomeUnknown`; that is the conservative direction. One `GraphApiHttp` bean, and so one `HttpClient` and connection pool, serves the send adapter, the template gateway and the ownership verifier. It stays inside the dispatcher's `dokene.messaging.send-timeout` (10s, ADR 0023 §7). The answer is read while streaming with the shared 256 KiB cap, and a declared or actual larger body is not read. The adapter makes exactly one `POST` per `send` and never retries.

### 4. Result classes

The adapter reads only the HTTP status, `messages[0].id`, `messages[0].message_status` and the integer `error.code`. Error text, `error_data.details`, `fbtrace_id`, headers and the raw body are never kept, logged or returned. `error_subcode` is deprecated by Meta and not used.

| Answer | Result | Why |
| --- | --- | --- |
| 2xx with a safe string id (`[A-Za-z0-9._:=+/-]{1,128}`, see §4.1) and `message_status` absent, `accepted` or `held_for_quality_assessment` | `Accepted(id)` | Acceptance is not delivery. Delivery comes only from verified webhooks. |
| 2xx with a safe id and any other status (for example `paused`) | `OutcomeUnknown(id)` | Meta assigned an id but did not accept it outright. A webhook may still settle it (T13). |
| 2xx that is malformed, has no id or a usable one, or is oversized | `OutcomeUnknown()` | Meta may have taken the message. |
| Deadline already passed, request could not be built, connection refused, connect timeout or failed TLS handshake (`SSLHandshakeException`) | `FailedTransiently(PROVIDER_UNAVAILABLE)` | The request provably never left: HTTP bytes are written only after the connection and the handshake complete. |
| Timeout, I/O failure or any other fault after sending (including a malformed response header such as a non-numeric `Content-Length`), or a redirect | `OutcomeUnknown()` | The `POST` may have been processed. Only a failure while building the request, a refused connection or a connect timeout counts as not sent. |
| 4xx, Meta throttling (`4`, `80007`, `130429`, `131048`, `131056`) or HTTP `429` | `FailedTransiently(RATE_LIMITED)` | A definitive refusal, so a later attempt is safe. |
| 4xx, Meta service-unavailable (`1`, `2`, `131000`, `131016`, `131057`) | `FailedTransiently(PROVIDER_UNAVAILABLE)` | Same reasoning. |
| 4xx, authorization (`0`, `3`, `10`, `190`, `200`–`299`, `131005`), or `401`/`403` without a code | `RejectedPermanently(AUTHENTICATION)` | |
| 4xx, recipient (`131021`, `131026`) | `RejectedPermanently(INVALID_RECIPIENT)` | |
| 4xx, integrity or policy (`368`, `130472`, `130497`, `131031`, `131047`, `131049`, `131050`) | `RejectedPermanently(POLICY_VIOLATION)` | |
| 4xx, template or parameter (`100`, `131008`, `131009`, `131051`, `132000`–`132999`, `135000`) | `RejectedPermanently(TEMPLATE_REJECTED)` | |
| `408` or `425` without a Meta code | `FailedTransiently(PROVIDER_UNAVAILABLE)` | An intermediary did not take the whole request. |
| Any other 4xx | `RejectedPermanently(UNKNOWN)` | |
| Any 5xx, with or without a Meta code (including `1`, `2` and `131016`), and gateway errors without a Meta body | `OutcomeUnknown()` | Acceptance cannot be ruled out, and a retry could send twice. See below. |

Nothing thrown, whether an exception or an `Error`, crosses the port. A 2xx `messages[0].id` that is not a JSON string is not an id, so the result is `OutcomeUnknown()`. An unexpected fault before the `POST` is `FailedTransiently(PROVIDER_UNAVAILABLE)`, and one after it is `OutcomeUnknown()`. A missing resolver or an unresolvable credential is `RejectedPermanently(AUTHENTICATION)`. A missing, disabled or unverified integration is `RejectedPermanently(POLICY_VIOLATION)`.

**Why every 5xx is uncertain.** Meta's [error code reference](https://developers.facebook.com/docs/whatsapp/cloud-api/support/error-codes) describes code `1` as an invalid request or possible server error, code `2` as temporary downtime or overload, and `131016` as the service being temporarily unavailable. None of them states that a send answered with a 5xx was not accepted or queued, including when the error is produced after processing. The Cloud API also offers no idempotency key (ADR 0023 §4.2). Without such a guarantee, a 5xx is never classified as retryable. This replaces the earlier rule, which treated 5xx with codes `1`, `2` and `131016` as transient (follow-up review on PR #227). A 4xx remains a definitive refusal, so its throttling and service-unavailable codes stay safely retryable, as do the provably pre-send failures above.

#### 4.1 Provider message id

Meta does not document a format for the id:
- the [Messages reference](https://developers.facebook.com/docs/whatsapp/cloud-api/reference/messages) lists the send response's `messages[].id` only as a `string`;
- the [status webhook reference](https://developers.facebook.com/documentation/business-messaging/whatsapp/webhooks/reference/messages/status) describes `statuses[].id` only as a "WhatsApp message ID";
- the `wamid.` prefix appears in examples, not in either contract.

The adapter therefore treats the id as **opaque**. The pattern above guarantees only that the id is a JSON string that can be stored (`VARCHAR(128)`, V18) and matched safely. No prefix is required until Meta documents one.

This is safe because the id is used only for exact, tenant-scoped equality:
- `OutboundMessageRepository.findIdsByProviderMessageId(tenantId, id)`, backed by the V18 index on `(tenant_id, provider_message_id)`;
- the equality check in `DefaultDeliveryStatusSink`.

An unexpected but well-formed id can never trigger a send, and it can never match another tenant's message. The worst case is that no verified webhook matches it: the message stays `SENT` without a delivery update, which is visible, and no duplicate is sent. Tests pin both a realistic `wamid.…` id and a prefix-less opaque id as accepted. Empty, whitespace, control-character, over-long and non-string ids are `OutcomeUnknown`.

### 5. Verification

Tests are offline only. `GraphApiMessagingProviderTest` replays the synthetic Meta answers in `backend/src/test/resources/contracts/whatsapp/graph-send-responses.json` from a loopback server. `GraphApiSendErrorsTest` pins the code table. Together they cover the request shape and parameter order, invalid commands, 4xx/5xx/429, redirects, a timeout after submission, an unreachable provider, malformed and oversized answers, fail-closed sender and secret paths, the single attempt, and a secret-bearing error body whose token, phone number, message text and trace id appear in no result or log. There is no live test. Live provider evidence belongs to the final integration (#142, #145).

## Alternatives considered

- **Treat every 5xx as transient.** This is simpler, but a 5xx after Meta accepted the message would lead to a second send.
- **Retry 5xx answers with Meta codes `1`, `2` or `131016` (the original rule).** This was rejected in the follow-up review: Meta's documentation does not guarantee that those answers mean the message was not taken, and an internal product decision cannot establish transport safety. The chosen rule, every 5xx is uncertain, accepts more manual reconciliation in exchange for never sending twice.
- **Require the `wamid.` prefix on provider message ids.** This was rejected because the prefix is not part of Meta's documented contract (§4.1).
- **Carry the secret reference in `OutboundSendCommand`.** This would put integration data into a `messaging` type. The adapter reads the integration and secret reference from the trusted tenant context, and the command carries only the non-secret `SenderRevision` it must match (ADR 0024, issue 137 addendum).
- **Upgrade to the newest Graph version now.** This was deferred. v23.0 is supported until 2027-10-08 and is already exercised by the other Graph adapters, and a bump should move all three together.

## Consequences

- #142 can wire dispatch against a real adapter. Sending stays impossible in practice until #142 provides a production `IntegrationSecretResolver` and #144 opens the kill switches.
- `OutcomeUnknown` is the expected result for opaque gateway errors and timeouts. Operators settle it through webhooks (T13) or manual resolution, never through an automatic retry.
- The Graph API version must be revisited before 2027-10-08.
