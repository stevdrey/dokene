# Issue 96 verification: AI failure handling, telemetry and audit

Scope: [ADR 0019](../adr/0019-ai-failure-handling-telemetry-and-audit.md). Verified on JDK 26 with `./gradlew check` in `backend/`:
931 tests, 0 failures, 0 skipped (includes Testcontainers/PostgreSQL integration tests).

| Acceptance criterion | Evidence |
|---|---|
| Provider failures map to stable categories | `OpenAiResponsesApiAdapterTest` (429/5xx/4xx/408/timeout/malformed/refusal → `AiFailureCategory`), `AiProviderConfigurationTest` (`NOT_AVAILABLE`), `FollowUpControllerTest.aiUnavailableReasonsAreStableAndFlagWhetherRetryingIsReasonable` |
| Follow-up workflows stay usable when AI is unavailable | `AiFailureHandlingIntegrationTest.persistentProviderOutageDegradesToRetryableUnavailableAndKeepsTheQueueUsable`, `NoAiProviderConfiguredIntegrationTest` |
| Retry is bounded, configurable, transient-only | `ResilientAiProviderTest` (max attempts, deadline budget, no retry for `NOT_AVAILABLE`/`REFUSED`/`INVALID_STRUCTURED_RESPONSE`/`REJECTED_REQUEST`/`CANCELLED`, interruption), `AiRetryProperties` validation |
| Retry cannot bypass gate/authorization | `AiOutcomeReportingTest.temporaryProviderRecoveryStillGoesThroughTheActionGate` |
| Metrics capture latency/status/usage without sensitive content | `MicrometerAiTelemetryTest` (tag allow-list: no tenant/customer/actor/correlation), `AiFailureHandlingIntegrationTest` (real registry counts a retry) |
| Audit is privacy-safe and distinguishes generated / refused / rejected / failed | `AuditEventTest`, `DurableAiOutcomeAuditAdapterTest`, `AuditIntegrationTest` (round trip, survives outer rollback, tenant isolation, DB rejects unsafe shapes and AI columns on other event types) |
| Raw prompts/responses/secrets absent from logs and audit | `AiOutcomeReportingTest.noSensitiveTextReachesLogsTelemetryOrAudit`, `ResilientAiProviderTest.unexpectedRuntimeFailures...`, adapter refusal test, `FollowUpControllerTest.unexpectedProviderOrStateFailuresNeverLeak...` |
| Tests for timeout, throttling, malformed output, recovery, gate rejection | `ResilientAiProviderTest`, `AiOutcomeReportingTest`, `AiFailureHandlingIntegrationTest` |
| Client error/recovery semantics documented | `docs/wiki/AI-and-Automation.md` ("AI Failure Handling, Telemetry and Audit"), ADR 0019 §6 |
| No new public diagnostics | `AiFailureHandlingIntegrationTest.metricsRegistryIsAvailableWithoutExposingAnyActuatorEndpoint` (no `@Endpoint` beans, `management.endpoints.access.default=none`) |
| Correlation propagation | `AuditRequestFilterTest` (MDC set/cleared, `X-Request-Id` echoed, inbound header ignored), adapter test for `X-Client-Request-Id` |

Not verified here: a live OpenAI call and the black-box script `scripts/verify-issue-59-security.sh` against a running server (needs a running stack); the property that no actuator endpoint is reachable is covered at the bean/config level only.
