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
| nginx `client_max_body_size` on `/webhooks` (unchanged) | 4m | — | Edge cap on one body |
| nginx `limit_conn` on `/webhooks` | 16 in total, 8 per client address | — | Further webhook requests get `503` from nginx before their body is read; refusals log at `info` |
| Frontend `/tmp` tmpfs (`compose.yaml`) | 128 MiB | — | Where nginx buffers request bodies; webhook bodies stay under 16 × 4 MiB = 64 MiB of it |

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
| After the code review follow-ups: `./gradlew test --tests 'io.github.stevdrey.dokene.integration.whatsapp.webhook.*'` and `./gradlew check` | 75 webhook unit tests; `check` passed: 2333 tests, 0 failures |
| `nginx -t` on the shipped `nginx.conf` (frontend image) | syntax ok |

## Dynamic load evidence

**Setup.** Final run on 2026-11-03 on one workstation (16 cores, 15 GiB RAM). Before it, every other local test environment (the two QA Compose stacks) was brought down and no other test was running.
- **Project:** an isolated Compose project with its own loopback ports, built from `compose.yaml` plus an override kept outside the repository.
- **Backend:** settings as in the audit: AI provider blank, and a local secret source with synthetic canary `APP_SECRET` and `WEBHOOK_VERIFY_TOKEN` entries.
- **Images:** two backend images, run one after the other on the same stack:
  - **baseline:** `main` at `6d04547`;
  - **patched:** commit `a9fbf03`, the code review follow-up. Later commits on this branch change only this record.
- **Traffic:** requests went straight to the backend port, with no nginx in front.
- **Attack shape:** the audit's, 600 POSTs of 4 000 000 bytes with an all-zero well-formed signature from 150 concurrent clients. Bursts ran back to back on a freshly started backend.

Memory is the container memory reported by `docker stats` (the audit's metric), sampled every second. The runtime image has `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`. Outcomes are HTTP statuses; `closed` means the server answered and closed before the client had finished sending, so the body was not read.

| Container limit | Build | Idle | After burst 1 | After burst 2 | After burst 3 | Outcomes per burst | Burst time |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 GiB | baseline | 361 MiB | 968 MiB | 1000 MiB | 1003 MiB (peak 1016, at the limit) | 600 × `403` | 6.5–7.2 s |
| 1 GiB | patched | 349 MiB | 643 MiB | 736 MiB | 794 MiB | 62–148 × `403`, 452–538 × `503` + `Retry-After: 5` | 1.0–1.1 s |
| 2 GiB | baseline | 792 MiB | 1.95 GiB | 1.95 GiB | 1.96 GiB (at the limit) | 600 × `403` | 3.2–3.9 s |
| 2 GiB | patched | 643 MiB | 920 MiB | 1.30 GiB | 1.35 GiB | 163–230 × `403`, 370–437 × `503`, ≤ 2 `closed` | 1.2–1.3 s |

Without a container limit only the baseline was run, in an earlier run the same day: it grew from 0.77 GiB to **4.11 GiB** in one burst, and the second burst (41 s) exhausted the host's memory. It was not repeated with either build, so the host would not be exhausted again.

Other scenarios at 1 GiB, 150 concurrent clients, one run per build:

| Scenario | Requests | Baseline | Patched |
| --- | --- | --- | --- |
| No signature header, 4 000 000 B | 600 | 600 × `403` after reading each body (3.8 s) | 600 × `403` before reading (0.7 s) |
| Declared length 5 000 000 B, over the bound | 300 | 267 × `403`, 33 `closed` | 267 × `403`, 33 `closed` |
| Unknown length (chunked) 8 000 000 B | 300 | 245 × `403`, 55 `closed` | 4 × `403`, 296 `closed` (refused at bound + 1) |
| Valid signed callback, before and after all bursts | 1 + 1 | `200` both times | `200` both times |
| `GET` handshake with the right token, then a wrong one | 2 + 2 | `200` with the challenge echoed, then `403` | same |
| Container memory after all scenarios | — | 910 MiB (peak 1024 MiB) | 706 MiB (peak 708 MiB) |
| Rejection `WARN` lines in the whole run | — | 1802 | 5 |

Neither build was OOM-killed or restarted under a cgroup limit. The difference is what the memory goes to:
- **Baseline:** fills the limit in the first burst, reads and HMACs every forged body, takes 3 to 7 times longer per burst, and writes one `WARN` line per refusal (1801 to 1802 per run).
- **Patched:** reads at most 8 bodies at a time and refuses the rest unread. It stayed 200 to 600 MiB below the limit and wrote 3 to 5 rejection lines per run in total.
- **Compared with the first commit:** the review follow-ups (exactly sized reads, no copy of the route's body) lowered the 1 GiB patched figures from 0.66 / 0.85 / 0.96 GiB to 0.64 / 0.74 / 0.79 GiB.
- **Patched growth:** the patched container still grows across bursts, but less each time. This is consistent with G1 committing heap toward its cap, not with a live set, which admission bounds to about 8 bodies.
- **Secrets:** no canary appeared in any backend log.

## Edge (nginx) evidence

This answers the review finding that the earlier runs bypassed nginx, and that nginx buffered webhook bodies on an uncapped tmpfs with no connection limit.

**Setup:** the same isolated stack plus the frontend image, built once with `main`'s `nginx.conf` (and its uncapped `/tmp` tmpfs) and once with this branch's.
- All traffic went through nginx; the backend was the patched build at a 1 GiB limit.
- Legitimate signed callbacks, static-page probes and the host bursts came from the host, which nginx sees as one address.
- The attackers ran in throwaway containers on the stack's network, one address each.
- Buffered-body size is the tmpfs usage inside the frontend container, sampled every 50 ms.

| Scenario | `main` nginx | This branch's nginx |
| --- | --- | --- |
| Forged burst from the host, 600 × 4 000 000 B, 150 concurrent (two bursts) | peak **182 / 204 MiB** buffered in `/tmp` (tmpfs capped only at ~7.5 GiB); 170–212 × `403`, the rest `503` from the backend | peak **30 / 25 MiB**; 111–113 × `403`, 487–489 × `503` (nginx and backend) |
| nginx `warn`/`error` lines over those bursts | 1200 (one per buffered body) | 0 |
| 6 attacker addresses × 120 forged 4 000 000 B uploads, 30 at a time each | — | static page `200` and signed callbacks `200` throughout |
| 1 attacker address, 40 slow uploads (1 KiB every 5 s) for 40 s | — | static page `200`, signed callbacks `200` throughout |
| 5 attacker addresses × 4 slow uploads (20, above the 16 total) | — | static page `200`; signed callbacks `503` while it lasted, `200` after |
| 200 signed callbacks from the host, 50 at a time | — | 136–159 × `200`, 41–64 × `503` (four runs) |
| After all scenarios: signed callback, handshake, wrong token | — | `200`, `200` with the challenge echoed, `403` |

**Other observations:**
- The frontend container used 13–20 MiB of memory, and neither container restarted.
- No canary appeared in any log.
- Proxy resource exhaustion itself was not reproduced on `main` (the earlier runs showed the backend side). The 182–204 MiB peaks show the unbounded growth that this change caps.
- A per-address limit of 4 was also tried. It accepted only 40–46 % of the same 200-callback burst, so 8 ships.

## Residual risk

- A forged but well formed request still costs one secret resolve and one HMAC over at most `max-body-bytes`, now bounded by the admission permits.
- An attacker who keeps every permit or edge slot busy delays legitimate callbacks, which get `503` and are retried by Meta (up to 36 hours or 7 days, per Meta's conflicting pages). Through the shipped nginx this takes slow uploads from 2 or more addresses; it does not affect the rest of the site.
- The per-address edge limit also defers legitimate bursts: from one address, 200 signed callbacks sent 50 at a time saw 68–80 % accepted and the rest `503`. Behind another proxy or tunnel, every client has one address unless `real_ip` restores it.
- The bound is per backend instance.
- A sustained flood can still make G1 commit heap toward `MaxRAMPercentage`: with a 1 GiB container the patched build stayed up at 0.79 GiB after three bursts. Production limits should leave headroom above the JVM heap cap.
- The bounds assume a body-buffering proxy in front of `/webhooks`, as the shipped nginx is configured (`proxy_request_buffering on`, `client_body_timeout 10s`). Exposed directly, a client that drips bodies slowly can hold the admission permits (Meta gets `503`) or, after a pre-admission refusal, Tomcat workers while Tomcat discards up to `server.tomcat.max-swallow-size` of the unread body. That pool is shared with every route.
