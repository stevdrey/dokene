# Issue #316 OpenAI output-token ceiling: verification record

Status: single PR. It closes audit finding L10 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md): neither OpenAI request (recommendation or draft) set an output-token limit, so the schema `maxLength`s were enforced only after generation and a request could keep generating, and billing, after the 15 s client timeout. The configuration is described in [ADR 0016](../adr/0016-openai-responses-api-adapter-with-structured-outputs.md) section 6 and the [AI and Automation](../wiki/AI-and-Automation.md) provider section.

This record is sanitized: it gives limits, outcomes and test names only. It contains no prompt, customer text, model output or secret.

## Environment

- **Run:** 2026-11-04, branch `fix/316-bound-openai-output-tokens`, based on `main` at `263ad4b` (the audit's tree `c4b62d1` plus later remediations). The code was rechecked on that base before the change: `OpenAiResponsesApiAdapter` built both `ResponseCreateParams` without `maxOutputTokens`, so L10 still applied as reported. **Tested revisions:** each group of results below names the commit whose tree it ran on. The last group ran on the tree of the commit that added it, which cannot name its own hash; it is the commit that follows `300bb56` on this branch.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build; `com.openai:openai-java` 4.94.0.
- **Data:** synthetic only. Provider responses come from the in-process `HttpServer` stub in `OpenAiResponsesApiAdapterTest`; no live OpenAI call.

## Configured limit

| Setting | Default | Range | Effect |
| --- | --- | --- | --- |
| `dokene.ai.openai.max-output-tokens` (`DOKENE_AI_OPENAI_MAX_OUTPUT_TOKENS`) | 4096 | 256 to 16384, startup fails outside it | Sent as `max_output_tokens` on both the recommendation and the draft request. Reasoning tokens count toward it |

The default lives only in `OpenAiProviderProperties.DEFAULT_MAX_OUTPUT_TOKENS`. `application.yml` and `compose.yaml` pass the variable through with an empty fallback, and an unset or empty value binds to that default.

### Why 4096 and not the schema maximum

The audit suggested the schema's maximum size plus headroom. That maximum is about 17.6k characters for a draft (body 1000, 20 variables of up to 550 characters, 20 evidence and warning items of up to 200, rationale 500) and about 12.3k for a recommendation, roughly 5k and 3.5k tokens of Spanish text. The ceiling was chosen from use instead:

- The real-provider baseline of issue 124 (`docs/verification/issue-124-evidence/live-baseline-graded.json`) reported 17,455 output tokens over 45 provider calls, about 390 per call, with a p95 latency of about 9 s.
- That is about 57 tokens/s over the whole call (390 tokens in a median 6.8 s). At that rate the 15 s timeout delivers roughly 850 tokens to the client, so a larger ceiling would mostly pay for output the client has already abandoned. The rate includes input processing, so pure generation may be faster; it is an estimate from that baseline, not measured for this change.

4096 is about ten times the observed average. The ceiling caps what one attempt can generate and bill, but it does not stop generation after the client times out: at the rate above, an abandoned attempt can still bill about 3.2k tokens beyond the roughly 850 the client received. The ceiling, not the schema, is therefore the effective draft size limit: a draft that fills every schema field to its maximum is cut off and fails safely (below), and the deterministic workflows are unaffected.

### Budget controls (assessment)

- **Per request:** bounded by the ceiling above.
- **Per user action:** at most `dokene.ai.retry.max-attempts` (default 2) provider attempts, since a timeout is retryable and the abandoned attempt may still generate up to the ceiling. Output cost is therefore at most 2 × 4096 tokens per action with the defaults, of which up to about 2 × 3.2k may be generated after the client gave up. An incomplete response is not retried.
- **Per tenant over time:** `dokene.ai.rate-limit` (60 per tenant and 20 per actor per minute, in memory per instance) bounds call rate only. There is no token or spend budget; the decision is tracked in [#346](https://github.com/stevdrey/dokene/issues/346).

## Automated coverage

| Acceptance item | Test |
| --- | --- |
| The recommendation request sends `max_output_tokens` = 4096 by default, and a representative valid outcome succeeds | `OpenAiResponsesApiAdapterTest.recommendationRequestCarriesTheDefaultOutputTokenCeiling` |
| A representative valid draft succeeds at the default ceiling, and a configured ceiling is sent | `OpenAiResponsesApiAdapterTest.draftRequestCarriesTheDefaultOutputTokenCeilingAndAValidDraftSucceeds`, `draftRequestCarriesTheConfiguredOutputTokenCeiling` |
| A recommendation cut off at the ceiling (status `incomplete`, reason `max_output_tokens`, partial JSON) fails as non-retryable `INVALID_STRUCTURED_RESPONSE` with rejection reason `OUTPUT_TOKEN_LIMIT` and status `FAILED`, keeps the reported usage and request id, returns no outcome, makes exactly one provider call, and leaks no partial output text in the message or the metadata | `OpenAiResponsesApiAdapterTest.recommendationTruncatedAtTheCeilingFailsAsInvalidWithoutAnOutcomeOrRetry` |
| The same for a draft | `OpenAiResponsesApiAdapterTest.draftTruncatedAtTheCeilingFailsAsInvalidWithoutAnOutcomeOrRetry` |
| An incomplete response for another reason (`content_filter`) stays `INVALID_STRUCTURED_RESPONSE` without the `OUTPUT_TOKEN_LIMIT` reason, for both operations | `OpenAiResponsesApiAdapterTest.recommendationIncompleteForAnotherReasonIsNotLabelledAsTheOutputTokenLimit`, `draftIncompleteForAnotherReasonIsNotLabelledAsTheOutputTokenLimit` |
| The evaluation grades a cut-off output as a schema failure, not as an allowlist or unsafe-content finding | `InvariantCheckerTest.anOutputCutOffAtTheTokenCeilingIsASchemaFailureNotAModelFinding` |
| The evaluation report names a cut-off output as `INVALID_STRUCTURED_RESPONSE (OUTPUT_TOKEN_LIMIT)` among the provider failures of an undelivered operation, in the JSON, the Markdown and the console summary, and an invalid response without that reason stays plain `INVALID_STRUCTURED_RESPONSE` | `EvalReportTest.anOutputCutOffAtTheTokenCeilingIsNamedInTheReportApartFromOtherInvalidResponses` |
| A provider `400` about the ceiling maps to `REJECTED_REQUEST` with one call and no provider text in the message or the metadata, for both operations | `OpenAiResponsesApiAdapterTest.draftProviderRejectionOfTheCeilingFailsAsRejectedRequest`, `recommendationProviderRejectionOfTheCeilingFailsAsRejectedRequest` |
| Existing representative outputs, refusals, status mapping and error mapping are unchanged | the other 36 tests of `OpenAiResponsesApiAdapterTest`, all at the default ceiling; the status handling of both operations now goes through one helper |
| Default 4096 when unset or empty; a configured value binds; 255, 16385, 0 and -1 fail startup naming only the property; 256 and 16384 are accepted | `OpenAiConfigurationTest.defaultsTheOutputTokenCeilingWhenUnset`, `anEmptyOutputTokenCeilingFallsBackToTheDefault`, `theShippedApplicationYamlGivesTheDefaultWhetherTheVariableIsUnsetOrEmpty` (loads the real `application.yml`: unset and empty give 4096, `2048` gives 2048), `bindsAConfiguredOutputTokenCeiling`, `failsStartupWhenTheOutputTokenCeilingIsOutOfRange`, `acceptsTheOutputTokenCeilingBounds` |
| An invalid structured response is not retried, is audited with its category, and degrades the request with no outcome delivered | existing `AiFailureHandlingIntegrationTest.malformedStructuredOutputIsNotRetriedAndIsAuditedWithItsCategory` and the non-retryable categories case of `ResilientAiProviderTest` (unchanged; the truncation tests above show a cut-off response takes this path) |
| The deterministic evaluation report still matches its committed baseline; the only baseline change is that case `ua-06`'s draft failure now names its scripted reason, `INVALID_STRUCTURED_RESPONSE (UNSAFE_CONTENT)` | `AiEvalDeterministicIntegrationTest` |

## Results

### `0d4129a`: the ceiling on both requests

| Command | Result |
| --- | --- |
| `./gradlew test --tests '*OpenAiResponsesApiAdapterTest' --tests '*OpenAiConfigurationTest'` before the change | did not compile (the new property did not exist) |
| The same after the change | 41 + 9 tests, 0 failures |
| `./gradlew check` | passed: 2374 tests, 0 failures, 0 skipped |

### `dce00fe`: first code review follow-ups

| Command | Result |
| --- | --- |
| `./gradlew test --tests '*OpenAiResponsesApiAdapterTest' --tests '*OpenAiConfigurationTest'` | 42 + 9 tests, 0 failures |
| `./gradlew check` | passed: 2375 tests, 0 failures, 0 skipped |
| `./gradlew spikeTest --tests '*OpenAiCallCancellationSpikeTest'` (opt-in, the other caller of the properties constructor) | 10 tests, 0 failures |

### `48f73ec`: the default kept only in the code

| Command | Result |
| --- | --- |
| `./gradlew test --tests '*OpenAiConfigurationTest' --tests '*OpenAiResponsesApiAdapterTest'` | 10 + 42 tests, 0 failures |
| `./gradlew check` | passed: 2376 tests, 0 failures, 0 skipped |
| `docker compose config backend` with the variable unset (read only, no container started) | the backend receives `DOKENE_AI_OPENAI_MAX_OUTPUT_TOKENS: ""`, which the configuration test binds to 4096 |

### `0784c17`: the shipped `application.yml` test

| Command | Result |
| --- | --- |
| `./gradlew test --tests '*OpenAiConfigurationTest'` | 11 tests, 0 failures |

### `300bb56`: reason `OUTPUT_TOKEN_LIMIT`, one status helper, 5-argument constructor removed

| Command | Result |
| --- | --- |
| `./gradlew compileTestJava` before the implementation | did not compile (`OUTPUT_TOKEN_LIMIT` did not exist) |
| `./gradlew test --tests '*OpenAiResponsesApiAdapterTest' --tests '*OpenAiConfigurationTest' --tests '*InvariantCheckerTest' --tests '*AiEvalDeterministicIntegrationTest'` | 45 + 11 + 25 + 1 tests, 0 failures; the deterministic report still matches its baseline |
| `./gradlew check spikeTest --tests '*OpenAiCallCancellationSpikeTest'` | passed: 2381 tests and 10 spike tests, 0 failures, 0 skipped |

### The commit after `300bb56`: the reason in the evaluation report

| Command | Result |
| --- | --- |
| `./gradlew test --tests '*EvalReportTest' --tests '*AiEvalDeterministicIntegrationTest' --tests '*InvariantCheckerTest'` with the committed baseline unchanged | `EvalReportTest` 31 tests and `InvariantCheckerTest` 25 tests, 0 failures; `AiEvalDeterministicIntegrationTest` failed on baseline drift, and the only differing field was `ua-06`'s draft failure gaining `(UNSAFE_CONTENT)` |
| The same after updating that one baseline line, then `./gradlew check spikeTest --tests '*OpenAiCallCancellationSpikeTest'` | passed: 2382 tests and 10 spike tests, 0 failures, 0 skipped |

Both compile tasks print a pre-existing `deprecated API` note, which also appears on `263ad4b`. The test run prints a JDK warning about Spring reflectively mutating `CsrfFilter.tokenRepository`; it is unrelated to this change.

## Not verified here

No live OpenAI run was made for this change. Before relying on the default with a different model, especially a reasoning model, run `./gradlew aiEvalLive` (ADR 0021) and compare coverage with the issue 124 baseline. An undelivered operation with cause `PROVIDER_FAILURE` would show the ceiling is too low.
