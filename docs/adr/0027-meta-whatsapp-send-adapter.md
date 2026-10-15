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
| Sender | Taken from server-side state, never from the command. The adapter reads the current tenant from the trusted context (dispatch is synchronous in that context, ADR 0024 §4) and the tenant's `WhatsAppIntegration`. A missing, disabled or unverified integration is refused without a call. |
| Credential | Resolved per send through `IntegrationSecretResolver` in `Tenant` scope, held in a `ResolvedSecret` for the call only, and sent only in the `Authorization: Bearer` header. |
| Body | Built by the adapter: `messaging_product=whatsapp`, `recipient_type=individual`, `to` (E.164), `type=template`, `template.name`, `template.language.code`, and one `body` component whose `text` parameters are exactly the command's bound parameters, in order. The component is omitted when there are no parameters. Free-form text is never sent (ADR 0025 §4). |
| Local validation | Before any call: E.164 recipient, `TemplateMapping.PROVIDER_TEMPLATE_NAME` and `LANGUAGE`, at most `TemplateMapping.MAX_PARAMETERS` parameters. Each parameter must be non-blank, at most 1024 characters, and contain no NUL, new line, tab or more than four consecutive spaces (a Meta rule). A failure is `RejectedPermanently` (`INVALID_RECIPIENT` or `TEMPLATE_REJECTED`) and nothing is sent. |
| Idempotency | Meta has no client idempotency token, so `sendKey` is not sent (ADR 0023 §4.2). Exactly-once rests on the dispatcher's persist-then-call discipline. |

### 3. Bounds

There is one deadline (`dokene.integration.whatsapp.graph.timeout`, default 5s) for connecting, sending, the headers and the body. It stays inside the dispatcher's `dokene.messaging.send-timeout` (10s, ADR 0023 §7). The answer is read while streaming with the shared 256 KiB cap, and a declared or actual larger body is not read. The adapter makes exactly one `POST` per `send` and never retries.

### 4. Result classes

The adapter reads only the HTTP status, `messages[0].id`, `messages[0].message_status` and the integer `error.code`. Error text, `error_data.details`, `fbtrace_id`, headers and the raw body are never kept, logged or returned. `error_subcode` is deprecated by Meta and not used.

| Answer | Result | Why |
| --- | --- | --- |
| 2xx with a safe `wamid` (`[A-Za-z0-9._:=+/-]{1,128}`) and `message_status` absent, `accepted` or `held_for_quality_assessment` | `Accepted(id)` | Acceptance is not delivery. Delivery comes only from verified webhooks. |
| 2xx with a safe id and any other status (for example `paused`) | `OutcomeUnknown(id)` | Meta assigned an id but did not accept it outright. A webhook may still settle it (T13). |
| 2xx that is malformed, has no id or a usable one, or is oversized | `OutcomeUnknown()` | Meta may have taken the message. |
| Deadline already passed, connection refused or connect timeout | `FailedTransiently(PROVIDER_UNAVAILABLE)` | The request provably never left. |
| Timeout or I/O failure after sending, or a redirect | `OutcomeUnknown()` | The `POST` may have been processed. |
| 4xx, Meta throttling (`4`, `80007`, `130429`, `131048`, `131056`) or HTTP `429` | `FailedTransiently(RATE_LIMITED)` | A definitive refusal, so a later attempt is safe. |
| 4xx, Meta service-unavailable (`1`, `2`, `131000`, `131016`, `131057`) | `FailedTransiently(PROVIDER_UNAVAILABLE)` | Same reasoning. |
| 4xx, authorization (`0`, `3`, `10`, `190`, `200`–`299`, `131005`), or `401`/`403` without a code | `RejectedPermanently(AUTHENTICATION)` | |
| 4xx, recipient (`131021`, `131026`) | `RejectedPermanently(INVALID_RECIPIENT)` | |
| 4xx, integrity or policy (`368`, `130472`, `130497`, `131031`, `131047`, `131049`, `131050`) | `RejectedPermanently(POLICY_VIOLATION)` | |
| 4xx, template or parameter (`100`, `131008`, `131009`, `131051`, `132000`–`132999`, `135000`) | `RejectedPermanently(TEMPLATE_REJECTED)` | |
| Any other 4xx | `RejectedPermanently(UNKNOWN)` | |
| 5xx with Meta code `1`, `2` or `131016` | `FailedTransiently(PROVIDER_UNAVAILABLE)` | Meta explicitly states the service was unavailable, so the message was not taken. |
| Any other 5xx, including gateway errors without a Meta body | `OutcomeUnknown()` | Acceptance cannot be ruled out, and a retry could send twice. |

No exception crosses the port. An unexpected fault before the `POST` is `FailedTransiently(PROVIDER_UNAVAILABLE)`, and one after it is `OutcomeUnknown()`. A missing resolver or an unresolvable credential is `RejectedPermanently(AUTHENTICATION)`. A missing, disabled or unverified integration is `RejectedPermanently(POLICY_VIOLATION)`.

### 5. Verification

Tests are offline only. `GraphApiMessagingProviderTest` replays the synthetic Meta answers in `backend/src/test/resources/contracts/whatsapp/graph-send-responses.json` from a loopback server. `GraphApiSendErrorsTest` pins the code table. Together they cover the request shape and parameter order, invalid commands, 4xx/5xx/429, redirects, a timeout after submission, an unreachable provider, malformed and oversized answers, fail-closed sender and secret paths, the single attempt, and a secret-bearing error body whose token, phone number, message text and trace id appear in no result or log. There is no live test. Live provider evidence belongs to the final integration (#142, #145).

## Alternatives considered

- **Treat every 5xx as transient.** This is simpler, but a 5xx after Meta accepted the message would lead to a second send.
- **Treat every 5xx as uncertain.** This never risks a duplicate, but it leaves explicit Meta "service unavailable" answers stuck for manual reconciliation. The mixed rule keeps the conservative default and honours only codes that state the message was not taken.
- **Carry the sender and secret reference in `OutboundSendCommand`.** This would change the frozen contract and put integration data into a `messaging` type. Reading them in the adapter from the trusted tenant context keeps credentials inside `integration`.
- **Upgrade to the newest Graph version now.** This was deferred. v23.0 is supported until 2027-10-08 and is already exercised by the other Graph adapters, and a bump should move all three together.

## Consequences

- #142 can wire dispatch against a real adapter. Sending stays impossible in practice until #142 provides a production `IntegrationSecretResolver` and #144 opens the kill switches.
- `OutcomeUnknown` is the expected result for opaque gateway errors and timeouts. Operators settle it through webhooks (T13) or manual resolution, never through an automatic retry.
- The Graph API version must be revisited before 2027-10-08.
