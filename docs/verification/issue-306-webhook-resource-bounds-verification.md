# Issue #306 webhook resource bounds: verification record

Status: single PR. It closes audit finding M1 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md): the anonymous `POST /webhooks/whatsapp` read up to 4 MiB per request before any cheap refusal, with no bound on concurrent requests and one `WARN` line per refusal. The design is in the [ADR 0024](../adr/0024-phase-3-messaging-contract-freeze.md) issue 306 addendum and the [Messaging and Integrations](../wiki/Messaging-and-Integrations.md) webhook sections.

This record is sanitized: it gives limits, outcomes and test names only. It contains no reproduction script, payload or secret.

## Environment

- **Run:** 2026-11-03, branch `306-securitym1-bound-resources-used-by-webhook-requests`, based on `main` at `6d04547` (the audit's tree `c4b62d1` plus the audit record). The code was rechecked on that base before the change: M1 still applied as reported. **Tested revision:** the working tree that became the first commit of this branch on top of `6d04547` (the commit that adds this record; a commit cannot name its own hash). Every check and run below used that tree.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build.
- **Database:** Testcontainers PostgreSQL for the integration tests.
- **Data:** synthetic only. Callbacks are built by `MetaCallbacks` and signed through `WebhookSigning` with test keys; no live Meta call.

## Configured limits

| Setting | Default | Range | Effect |
| --- | --- | --- | --- |
| `dokene.integration.whatsapp.webhook.max-body-bytes` (`DOKENE_INTEGRATION_WHATSAPP_WEBHOOK_MAX_BODY_BYTES`) | 4194304 | 1 to 16777216 | Largest body read. A larger declared `Content-Length` is refused before any read; an unknown or understated length is refused after at most one byte past the bound |
| `dokene.integration.whatsapp.webhook.max-concurrent-requests` (`DOKENE_INTEGRATION_WHATSAPP_WEBHOOK_MAX_CONCURRENT_REQUESTS`) | 8 | 1 to 64 | Callbacks read, verified and recorded at once. One more is answered `503` with `Retry-After: 5`, unread |
| Rejection log interval (`WebhookRejectionLog.INTERVAL`, fixed) | 1 minute | — | First `WARN` line per reason, then one per reason per minute with ` suppressed=<n>` |
| nginx `client_max_body_size` on `/webhooks` (unchanged) | 4m | — | Edge cap in front of the backend; no edge connection or rate limit ships by default |

Worst case webhook body heap is about `max-concurrent-requests × max-body-bytes`, 32 MiB with the defaults, whatever the request rate. In the audit run the same route grew the backend from 714 MiB to 4.25 GiB.

## Automated coverage

| Acceptance item | Test |
| --- | --- |
| A missing, duplicated or malformed `X-Hub-Signature-256` is `403` with **zero** body bytes read and no secret resolve | `WhatsAppWebhookControllerTest.aMissingDuplicatedOrMalformedHeaderIsRefusedWithoutReadingTheBody` |
| An unset app secret reference is `403` with zero body bytes read | `WhatsAppWebhookControllerTest.anUnsetAppSecretIsRefusedWithoutReadingTheBody` |
| A declared `Content-Length` over the bound is `403` with zero bytes read, no permit taken and no HMAC | `WhatsAppWebhookControllerTest.aDeclaredLengthOverTheBoundIsRefusedWithoutReadingOrTakingAPermit` |
| An unknown (`-1`) or dishonest (`1`) length on a 16 KiB body under a 4 KiB bound reads exactly bound + 1 bytes, is `403`, and computes no HMAC | `WhatsAppWebhookControllerTest.aLyingOrUnknownLengthStillCostsAtMostOneBytePastTheBoundAndNoHmac`, `BoundedWebhookBodyReaderTest` |
| With every permit held, a valid signed callback is `503` with `Retry-After`, an empty body, zero bytes read, no ingestion, and one `dokene.webhook.overloaded` count; once permits are free the same callback is `200` | `WhatsAppWebhookControllerTest.aSaturatedRouteAnswers503WithRetryAfterBeforeReadingAByte` |
| The permit is returned after `200`, a signature mismatch (`403`), a schema refusal (`400`) and an ingestion failure that propagates to the outage boundary | `WhatsAppWebhookControllerTest.aSignedCallbackIsReadVerifiedAndRecordedUnderAPermitThatIsReturned`, `thePermitIsReturnedAfterAMismatchASchemaRefusalAndAFailingIngestion` |
| 32 threads racing for 3 permits: peak in flight is exactly 3, 29 are refused, all permits are returned; a double close returns one permit | `WebhookAdmissionTest` |
| 1000 refusals of one reason in a minute write one line; the next line after the minute carries `suppressed=1000`; reasons are throttled independently. Telemetry still counts every refusal | `WebhookRejectionLogTest`, and the per-refusal counter assertions in `WebhookSignatureVerifierTest`, `WebhookChallengeVerifierTest`, `BoundedWebhookBodyReaderTest` |
| `prepare` refuses every header and reference failure without a resolver call; a prepared check resolves once only when the body arrives | `WebhookSignatureVerifierTest.prepareRefusesABadHeaderOrAnUnsetReferenceWithoutAResolverCall`, `aPreparedCheckResolvesOnceOnlyWhenTheBodyArrives` |
| The concurrency bound defaults to 8, fails startup outside 1 to 64 naming only the property, and `dokene.webhook.overloaded` is a Micrometer counter | `WhatsAppWebhookConfigurationTest.theConcurrencyBoundIsValidatedAtStartupAndDefaultsToEight`, `theMicrometerTelemetryCountsOverloadedCallbacks` |
| Valid signed callbacks and the `GET` handshake still work end to end; an unsigned `application/x-www-form-urlencoded` body is `403` with no session, so no filter parses it before the route | `WhatsAppWebhookIntegrationTest` (12 tests, including `onlyTheSignatureAuthenticatesTheRouteWhichAloneSkipsCsrfAndBoundsItsBody`), `WebhookChainStatelessIntegrationTest`, `ApprovedSendJourneyIntegrationTest`, `EnglishTemplateSendJourneyIntegrationTest` |
| No secret, signature or body canary reaches any log line | the `LogCapture` canary checks in the verifier, reader and configuration tests |

## Results

| Command | Result |
| --- | --- |
| `./gradlew test --tests 'io.github.stevdrey.dokene.integration.whatsapp.webhook.*'` | 71 tests, 0 failures |
| `./gradlew test --tests '*WhatsAppWebhookIntegrationTest' --tests '*WebhookChainStatelessIntegrationTest' --tests '*EnglishTemplateSendJourneyIntegrationTest' --tests '*ApprovedSendJourneyIntegrationTest' --tests '*Phase3ErrorPageFilterTest'` | 27 tests, 0 failures |
| `./gradlew check` | passed: 2329 tests, 0 failures |

## Dynamic load evidence

**Setup.** Run on 2026-11-03 on one workstation (16 cores, 15 GiB RAM, other local stacks running).
- **Project:** an isolated Compose project with its own loopback ports, built from `compose.yaml` plus an override kept outside the repository.
- **Backend:** settings as in the audit: AI provider blank, and a local secret source with synthetic canary `APP_SECRET` and `WEBHOOK_VERIFY_TOKEN` entries.
- **Images:** two backend images, run one after the other on the same stack:
  - **baseline:** `main` at `6d04547`;
  - **patched:** the tested revision above.
- **Traffic:** requests went straight to the backend port, with no nginx in front.
- **Attack shape:** the audit's, 600 POSTs of 4 000 000 bytes with an all-zero well-formed signature from 150 concurrent clients. Bursts ran back to back on a freshly started backend.

Memory is the container memory reported by `docker stats` (the audit's metric), sampled every second. The runtime image has `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`. Outcomes are HTTP statuses; `closed` means the server answered and closed before the client had finished sending, so the body was not read.

| Container limit | Build | Idle | After burst 1 | After burst 2 | After burst 3 | Outcomes per burst | Burst time |
| --- | --- | --- | --- | --- | --- | --- | --- |
| none | baseline | 0.77 GiB | **4.11 GiB** | run stopped: the host ran out of memory during burst 2 (41 s) | — | 600 × `403` | 8.0 s, then 41.3 s |
| 2 GiB | baseline | 0.93 GiB | 1.92 GiB | 1.93 GiB | 1.93 GiB (at the limit) | 600 × `403` | 3.4–4.0 s |
| 2 GiB | patched | 0.88 GiB | 1.01 GiB | 0.94 GiB | 1.36 GiB | 107–178 × `403`, 419–493 × `503` + `Retry-After: 5`, ≤ 3 `closed` | 1.3–1.4 s |
| 1 GiB | baseline | 0.35 GiB | 1.00 GiB (peak at the limit) | — | — | 600 × `403` | 7.1 s |
| 1 GiB | patched | 0.35 GiB | 0.66 GiB | 0.85 GiB | 0.96 GiB | 42–108 × `403`, 492–558 × `503` | 1.3–2.5 s |

The unlimited run was not repeated for the patched build, so that the host would not be exhausted again.

Other scenarios against the patched build at 1 GiB, 150 concurrent clients:

| Scenario | Requests | Outcomes |
| --- | --- | --- |
| No signature header, 4 000 000 B | 600 | 404 × `403`, 196 `closed` (refused before reading) |
| Declared length 5 000 000 B, over the bound | 300 | 149 × `403`, 151 `closed` |
| Unknown length (chunked) 8 000 000 B | 300 | 3 × `403`, 297 `closed` (refused at bound + 1) |
| Valid signed callback, before and after all bursts | 1 + 1 | `200` both times |
| `GET` handshake with the right token, then a wrong one | 2 + 2 | `200` with the challenge echoed, then `403` |

Neither build was OOM-killed or restarted under a cgroup limit. The difference is what the memory goes to:
- **Baseline:** fills the limit at once, reads and HMACs every forged body, and writes one `WARN` line per refusal (1801 to 1802 lines per run).
- **Patched:** reads at most 8 bodies at a time, refuses the rest unread, and wrote 3 to 5 rejection lines per run in total.
- **Patched growth:** the patched container still grows across bursts, but not in proportion to requests (burst 2 under 2 GiB came out lower than burst 1). This is consistent with G1 committing heap toward its cap. It is not a live set, which admission bounds to about 8 bodies plus their copies.
- **Secrets:** no canary appeared in any backend log.

## Residual risk

- A forged but well formed request still costs one secret resolve and one HMAC over at most `max-body-bytes`, now bounded by the admission permits.
- An attacker who keeps every permit busy delays legitimate callbacks, which get `503` and are retried by Meta (up to 36 hours or 7 days, per Meta's conflicting pages). An edge `limit_conn`/`limit_req` zone can be added per deployment; a per-IP one can throttle Meta's own bursts.
- The bound is per backend instance.
- Each admitted request still allocates the body more than once (the reader's growing buffer, then the verified copy), so a sustained flood keeps G1 committing heap toward `MaxRAMPercentage`. With a 1 GiB container the patched build stayed up but used 0.96 GiB after three bursts, so production limits should leave headroom above the JVM heap cap.
