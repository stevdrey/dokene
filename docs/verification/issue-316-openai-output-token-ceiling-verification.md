# Issue #316 OpenAI output-token ceiling: verification record

Status: single PR. It closes audit finding L10 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md): neither OpenAI request (recommendation or draft) set an output-token limit, so the schema `maxLength`s were enforced only after generation and a request could keep generating, and billing, after the 15 s client timeout. The configuration is described in [ADR 0016](../adr/0016-openai-responses-api-adapter-with-structured-outputs.md) section 6 and the [AI and Automation](../wiki/AI-and-Automation.md) provider section.

This record is sanitized: it gives limits, outcomes and test names only. It contains no prompt, customer text, model output or secret.

## Environment

- **Run:** 2026-11-04, branch `fix/316-bound-openai-output-tokens`, based on `main` at `263ad4b` (the audit's tree `c4b62d1` plus later remediations). The code was rechecked on that base before the change: `OpenAiResponsesApiAdapter` built both `ResponseCreateParams` without `maxOutputTokens`, so L10 still applied as reported. **Tested revision:** the working tree that became the first commit of this branch on top of `263ad4b` (a commit cannot name its own hash). Every check below used that tree.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build; `com.openai:openai-java` 4.94.0.
- **Data:** synthetic only. Provider responses come from the in-process `HttpServer` stub in `OpenAiResponsesApiAdapterTest`; no live OpenAI call.

## Configured limit

| Setting | Default | Range | Effect |
| --- | --- | --- | --- |
| `dokene.ai.openai.max-output-tokens` (`DOKENE_AI_OPENAI_MAX_OUTPUT_TOKENS`) | 4096 | 256 to 16384, startup fails outside it | Sent as `max_output_tokens` on both the recommendation and the draft request. Reasoning tokens count toward it |

### Why 4096 and not the schema maximum

The audit suggested the schema's maximum size plus headroom. That maximum is about 17.6k characters for a draft (body 1000, 20 variables of up to 550 characters, 20 evidence and warning items of up to 200, rationale 500) and about 12.3k for a recommendation, roughly 5k and 3.5k tokens of Spanish text. The ceiling was chosen from use instead:

- The real-provider baseline of issue 124 (`docs/verification/issue-124-evidence/live-baseline-graded.json`) reported 17,455 output tokens over 45 provider calls, about 390 per call, with a p95 latency of about 9 s.
- At the observed generation rate the 15 s timeout delivers far fewer than 4096 tokens to the client, so a larger ceiling would only pay for output the client has already abandoned.

4096 is about ten times the observed average. A draft that fills every schema field to its maximum is cut off and fails safely (below); the deterministic workflows are unaffected.

### Budget controls (assessment)

- **Per request:** bounded by the ceiling above.
- **Per user action:** at most `dokene.ai.retry.max-attempts` (default 2) provider attempts, since a timeout is retryable and the abandoned attempt may still generate up to the ceiling. Output cost is therefore at most 2 × 4096 tokens per action with the defaults. An incomplete response is not retried.
- **Per tenant over time:** `dokene.ai.rate-limit` (60 per tenant and 20 per actor per minute, in memory per instance) bounds call rate only. There is no token or spend budget; the decision is tracked in [#346](https://github.com/stevdrey/dokene/issues/346).

## Automated coverage

| Acceptance item | Test |
| --- | --- |
| The recommendation request sends `max_output_tokens` = 4096 by default | `OpenAiResponsesApiAdapterTest.recommendationRequestCarriesTheDefaultOutputTokenCeiling` |
| The draft request sends a configured ceiling, and a representative valid draft still succeeds | `OpenAiResponsesApiAdapterTest.draftRequestCarriesTheConfiguredOutputTokenCeiling` |
| A recommendation cut off at the ceiling (status `incomplete`, reason `max_output_tokens`, partial JSON) fails as non-retryable `INVALID_STRUCTURED_RESPONSE` with status `FAILED`, keeps the reported usage and request id, returns no outcome, makes exactly one provider call, and leaks no output text in the message | `OpenAiResponsesApiAdapterTest.recommendationTruncatedAtTheCeilingFailsAsInvalidWithoutAnOutcomeOrRetry` |
| The same for a draft | `OpenAiResponsesApiAdapterTest.draftTruncatedAtTheCeilingFailsAsInvalidWithoutAnOutcomeOrRetry` |
| A provider `400` about the ceiling maps to `REJECTED_REQUEST` with one call and no provider text in the message | `OpenAiResponsesApiAdapterTest.draftProviderRejectionOfTheCeilingFailsAsRejectedRequest` |
| Existing representative outputs, refusals, status mapping and error mapping are unchanged | the other 36 tests of `OpenAiResponsesApiAdapterTest` |
| Default 4096 when unset; a configured value binds; 255, 16385, 0 and -1 fail startup naming only the property; 256 and 16384 are accepted; the 5-argument constructor keeps the default | `OpenAiConfigurationTest.defaultsTheOutputTokenCeilingWhenUnset`, `bindsAConfiguredOutputTokenCeiling`, `failsStartupWhenTheOutputTokenCeilingIsOutOfRange`, `acceptsTheOutputTokenCeilingBounds` |
| An invalid structured response is not retried, is audited with its category, and degrades the request with no outcome delivered | existing `AiFailureHandlingIntegrationTest.malformedStructuredOutputIsNotRetriedAndIsAuditedWithItsCategory` and the non-retryable categories case of `ResilientAiProviderTest` (unchanged; the truncation tests above show a cut-off response takes this path) |
| The deterministic evaluation report still matches its committed baseline | `AiEvalDeterministicIntegrationTest` |

## Results

| Command | Result |
| --- | --- |
| `./gradlew test --tests '*OpenAiResponsesApiAdapterTest' --tests '*OpenAiConfigurationTest'` before the change | did not compile (the new property did not exist) |
| The same after the change | 41 + 9 tests, 0 failures |
| `./gradlew check` | passed: 2374 tests, 0 failures, 0 skipped |

Both compile tasks print a pre-existing `deprecated API` note, which also appears on `263ad4b`. The test run prints a JDK warning about Spring reflectively mutating `CsrfFilter.tokenRepository`; it is unrelated to this change.

## Not verified here

No live OpenAI run was made for this change. Before relying on the default with a different model, especially a reasoning model, run `./gradlew aiEvalLive` (ADR 0021) and compare coverage with the issue 124 baseline. An undelivered operation with cause `PROVIDER_FAILURE` would show the ceiling is too low.
