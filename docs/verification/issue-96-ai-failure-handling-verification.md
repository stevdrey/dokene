# Issue 96 verification: AI failure handling, telemetry and audit

Scope: [ADR 0019](../adr/0019-ai-failure-handling-telemetry-and-audit.md). Verified on JDK 26 with `./gradlew check` in `backend/` (includes Testcontainers/PostgreSQL integration tests); see the PR for the final test count.

| Acceptance criterion | Evidence |
|---|---|
| Provider failures map to stable categories | `OpenAiResponsesApiAdapterTest` (429/5xx/4xx/408/timeout/malformed/refusal → `AiFailureCategory`), `AiProviderConfigurationTest` (`NOT_AVAILABLE`), `FollowUpControllerTest.aiUnavailableReasonsAreStableAndFlagWhetherRetryingIsReasonable` |
| Follow-up workflows stay usable when AI is unavailable | `AiFailureHandlingIntegrationTest.persistentProviderOutageDegradesToRetryableUnavailableAndKeepsTheQueueUsable`, `NoAiProviderConfiguredIntegrationTest` |
| Retry is bounded, configurable, transient-only | `ResilientAiProviderTest` (max attempts, deadline budget, no retry for `NOT_AVAILABLE`/`REFUSED`/`INVALID_STRUCTURED_RESPONSE`/`REJECTED_REQUEST`/`CANCELLED`, interruption), `AiRetryProperties` validation |
| Retry cannot bypass gate/authorization | `AiOutcomeReportingTest.temporaryProviderRecoveryStillGoesThroughTheActionGate` |
| Metrics capture latency/status/usage without sensitive content | `MicrometerAiTelemetryTest` (tag allow-list: no tenant/customer/actor/correlation), `AiFailureHandlingIntegrationTest` (real registry counts a retry) |
| Audit is privacy-safe and distinguishes generated / refused / rejected / failed | `AuditEventTest`, `DurableAiOutcomeAuditAdapterTest`, `AuditIntegrationTest` (round trip, survives outer rollback, tenant isolation, DB rejects unsafe shapes and AI columns on other event types) |
| Audit never stores an unverified customer id; DB boundary rejects NULL AI columns | `AiOutcomeReportingTest` (`UNAUTHORIZED`/`CUSTOMER_NOT_FOUND`/`NO_TENANT_CONTEXT` counted and logged, never audited, exceptions unchanged), `DurableAiOutcomeAuditAdapterTest`, `AuditIntegrationTest` (V14: NULL columns and boundary details rejected) |
| Raw prompts/responses/secrets absent from logs and audit | `AiOutcomeReportingTest.noSensitiveTextReachesLogsTelemetryOrAudit`, `ResilientAiProviderTest.unexpectedRuntimeFailures...`, adapter refusal test, `FollowUpControllerTest.unexpectedProviderOrStateFailuresNeverLeak...` |
| Tests for timeout, throttling, malformed output, recovery, gate rejection | `ResilientAiProviderTest`, `AiOutcomeReportingTest`, `AiFailureHandlingIntegrationTest` |
| Client error/recovery semantics documented | `docs/wiki/AI-and-Automation.md` ("AI Failure Handling, Telemetry and Audit"), ADR 0019 §6 |
| No new public diagnostics | `AiFailureHandlingIntegrationTest.metricsRegistryIsAvailableWithoutExposingAnyActuatorEndpoint` (no `@Endpoint` beans, `management.endpoints.access.default=none`) |
| Throttling honors Retry-After; backoff bounded | `ResilientAiProviderTest` (hint honored / skipped when it exceeds the budget / only for `THROTTLED` / no-hint floor, backoff ceiling and no overflow), `OpenAiResponsesApiAdapterTest` (`retry-after`, `retry-after-ms`, unparseable) |
| Per-attempt latency stays per attempt | `ResilientAiProviderTest.syntheticRuntimeFailureLatencyIsMeasuredPerAttemptNotFromTheStartOfTheInvocation` |
| Authorization lost mid-failure still yields one counted/logged outcome | `AiOutcomeReportingTest` (`authorizationLostWhileAProviderFailureIsInFlight...`, recommendation and draft) |
| One logical outcome per request | `MicrometerAiTelemetryTest` (`attempts` vs `outcomes`), `AiOutcomeReportingTest` (retry = 2 attempts, 1 outcome; discarded output audited as `STALE_STATE`) |
| 500/503 stay diagnosable | `FollowUpControllerTest.unexpectedFailuresAreLoggedWithClassAndLocationButNeverWithTheirMessage` |
| Correlation propagation | `AuditRequestFilterTest` (MDC set/cleared, `X-Request-Id` echoed, inbound header ignored), adapter test for `X-Client-Request-Id`, `TenantSecurityConfigurationTest` (CORS exposes `X-Request-Id`), `LoggingConfigurationTest` (the shipped `logging.pattern.correlation`; the rendered log line itself is not asserted end to end) |

Not verified here: a live OpenAI call and the black-box script `scripts/verify-issue-59-security.sh` against a running server (needs a running stack); the property that no actuator endpoint is reachable is covered at the bean/config level only.
