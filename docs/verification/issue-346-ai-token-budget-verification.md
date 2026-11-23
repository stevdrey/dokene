# Issue #346 per-tenant AI token budget: verification record

Status: single PR. It completes the follow-up of audit finding L10 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md) that [#316](https://github.com/stevdrey/dokene/issues/316) left open: spend over time was unbounded per tenant. The decision is [ADR 0035](../adr/0035-per-tenant-monthly-ai-token-budget.md); the behavior is described in [AI and Automation](../wiki/AI-and-Automation.md#monthly-token-budget) and the [Security Model](../wiki/Security-Model.md#rate-limiting-and-abuse-protection).

This record is sanitized: it gives limits, outcomes and test names only. It contains no prompt, customer text, model output or secret.

## Environment

- **Run:** 2026-11-20, with a second run on 2026-11-23 after review fixes, branch `346-securityl10-decide-on-a-per-tenant-ai-token-budget`, based on `main` at `571641ee`.
- **Toolchain:** Gradle Wrapper with the Java 27 toolchain declared by the build, pinned to Temurin `27.0.1.1+4` (`scripts/check-java4shell-runtime.sh --require-patched` reports `PATCHED`; toolchain auto-detection off, so no other installed JDK 27 could be picked).
- **Database:** PostgreSQL from the image pinned in `compose.yaml`, through Testcontainers, with the real migrations (V1 to V42), the runtime and migration roles and forced row-level security.
- **Data:** synthetic only. Provider usage comes from a Mockito spy on the deterministic fake provider; no live OpenAI call.

## Decisions (ADR 0035)

| Open question of the Issue | Decision |
| --- | --- |
| Unit and window | Provider-reported input plus output tokens per UTC calendar month |
| Storage across instances and restarts | `dokene.ai_token_usage` (V42), one row per tenant and month, atomic upsert, forced RLS, no runtime `DELETE` |
| At the limit | No provider call; `AI_UNAVAILABLE` with `TOKEN_BUDGET_EXHAUSTED` (`retryable: false`); deterministic workflows unchanged; audited as `AI_INVOCATION_OUTCOME` / `FAILED` / `TOKEN_BUDGET_EXHAUSTED` |
| Who can see or change it | The deployment operator, through `dokene.ai.token-budget.monthly-tokens` (default 2,000,000, must be positive); tenant members cannot |
| Failed and incomplete attempts | Counted, per attempt, retries included, whenever the provider reports usage |

## Automated coverage

| Acceptance item | Test |
| --- | --- |
| A tenant over budget makes no provider call, gets `AI_UNAVAILABLE` / `TOKEN_BUDGET_EXHAUSTED` (not retryable) with the deterministic evaluation, is audited with that detail, and its due queue still lists the customer | `AiTokenBudgetIntegrationTest.aTenantOverBudgetMakesNoProviderCallAndKeepsTheDeterministicWorkflow` |
| The same for drafts | `AiTokenBudgetIntegrationTest.anExhaustedTenantGetsNoDraftEitherAndNoProviderCall` |
| Draft usage counts against the same budget | `AiTokenBudgetIntegrationTest.draftUsageIsCountedAgainstTheSameBudget` |
| A throttled attempt that reported usage and the successful retry are both counted | `AiTokenBudgetIntegrationTest.failedAndRetriedAttemptsAreCountedBecauseTheProviderBillsThem` |
| A terminal (non-retryable) provider failure still consumes the usage it reported and can exhaust the budget | `AiTokenBudgetIntegrationTest.aTerminalProviderFailureStillConsumesTheReportedBudget` |
| The budget is isolated per tenant: an exhausted tenant does not affect another, and RLS stops one tenant from reading or incrementing another's counter | `AiTokenBudgetIntegrationTest.theBudgetIsIsolatedPerTenant` |
| The runtime role cannot delete a counter | `AiTokenBudgetIntegrationTest.theRuntimeRoleCannotDeleteAUsageCounter` |
| The runtime role can only increase a counter: a decrease of either count or a change of month is rejected by the trigger, and a later increment still works | `AiTokenBudgetIntegrationTest.theRuntimeRoleCanOnlyIncreaseACounter` |
| The rate limit runs before the budget, so an exhausted tenant's refusals are throttled; a disabled provider skips the budget read and keeps reporting `NOT_AVAILABLE`; the meter sits inside the retry decorator; no tenant context applies neither | `AiInvocationRunnerTest` (4 tests) |
| No follow-up class other than `AiInvocationRunner` and `AiTokenBudget` calls the provider, with a negative fixture | `ModuleDependencyArchitectureTest.followUpReachesTheAiProviderOnlyThroughTheBudgetedRunner`, `aDirectFollowUpProviderCallFailsTheRunnerRule` |
| A write failure suppressed behind a provider failure is logged with the lost counts and failure type only; the unmetered meter is transparent | `AiTokenBudgetTest.aSuppressedWriteFailureIsLoggedWithTheLostCountsOnly`, `theUnmeteredMeterIsTransparentAndWritesNothing` |
| V42 is applied and the table is owned by the migration role | `TenantPersistenceIntegrationTest.migratesTheTenantFoundationWithLeastPrivilegeRuntimeAccess` |
| Refusal before any provider call; UTC calendar month (a Guayaquil evening on 30 November counts in December); last month ignored; per-tenant counters; failed, successful and usage-less attempts; one write per invocation; nothing written for zero usage; a write failure fails a successful call but never masks a provider failure; delegate timeouts kept; positive limit with default | `AiTokenBudgetTest` (10 tests) |
| The audit vocabulary covers the new reason | `DurableAiOutcomeAuditAdapterTest` (existing exhaustive switch; the build fails without the mapping) |
| The SPA explains the exhausted budget and offers no retry | `AiAssistantPanel.test.tsx` › `explains an exhausted monthly AI budget without offering a retry` |

## Results

| Command (working directory) | Result |
| --- | --- |
| `./gradlew test --tests '*AiTokenBudgetTest' --tests '*DurableAiOutcomeAuditAdapterTest'` (`backend`) | passed |
| `./gradlew test --tests '*AiTokenBudgetIntegrationTest' --tests '*TenantPersistenceIntegrationTest'` (`backend`) | passed after one test fix: the RLS assertion first read Spring's wrapper message instead of the root PostgreSQL error |
| `./gradlew check -Porg.gradle.java.installations.auto-detect=false -Porg.gradle.java.installations.paths=<Temurin 27.0.1.1+4>` (`backend`) | `BUILD SUCCESSFUL`, 2,976 tests, 0 failures, 0 skipped |
| After the review fixes (trigger, rate limit before budget, shared runner, disabled provider, logged loss): `./gradlew test --tests '*AiTokenBudget*' --tests '*AiInvocationRunnerTest' --tests '*ModuleDependencyArchitectureTest' --tests 'FollowUpRecommendationService*' --tests 'FollowUpDraftService*' --tests '*AiOutcomeReportingTest' --tests '*RecommendationCancellationSpikeTest' --tests '*AiFailureHandlingIntegrationTest' --tests '*TenantPersistenceIntegrationTest'` (`backend`) | 189 tests, 0 failures |
| After the review fixes: `./gradlew check` with the same toolchain pinning (`backend`) | `BUILD SUCCESSFUL`, 2,985 tests, 0 failures, 0 skipped |
| `npx vitest run src/features/followups` (`frontend`) | 289 passed, 3 failed. The 3 failures (`FollowUpWorkbench.test.tsx` › `uses a shrinkable queue grid at 390/768/1280 px`, `minmax(0, 1fr)` versus `minmax(0px, 1fr)`) fail the same way on the base without this change (288 passed, 3 failed) and are unrelated |
| `npx tsc -b` (`frontend`) | no errors |

## Limitations

- The budget is an admission threshold on recorded usage, not a ceiling on total tokens or spend (ADR 0035 §6); no test drives concurrent requests across the threshold.
- `compose.yaml` forwards `DOKENE_AI_TOKEN_BUDGET_MONTHLY_TOKENS` to the backend: `docker compose config backend` renders `"2000000"` when unset and `"12345"` when set to 12345 (read only, no container started).
- No live provider was called; usage was injected through the fake provider. The OpenAI adapter already maps reported usage into the metadata on success and failure (#316).
