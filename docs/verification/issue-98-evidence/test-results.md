# Issue 98 test evidence

Command: `cd backend && ./gradlew test` (JDK 26, Gradle 9.8.0, Testcontainers postgres:17-alpine). Result: BUILD SUCCESSFUL.

Full suite: 1037 tests, 0 skipped, 0 failed (after the sixth Codex review follow-up).

Evaluation and gate suites:

| Suite | Tests | Skipped | Failed |
|---|--:|--:|--:|
| AiProviderExceptionTest | 2 | 0 | 0 |
| DraftSafetyValidatorTest | 38 | 0 | 0 |
| AiEvalDatasetTest | 6 | 0 | 0 |
| AiEvalDeterministicIntegrationTest | 1 | 0 | 0 |
| EvalReportTest | 13 | 0 | 0 |
| InvariantCheckerParityTest | 3 | 0 | 0 |
| InvariantCheckerTest | 21 | 0 | 0 |
| RecordingAiProviderTest | 3 | 0 | 0 |
| OpenAiResponsesApiAdapterTest | 33 | 0 | 0 |
| DefaultAiActionGateTest | 59 | 0 | 0 |

Additional checks run:

- `./gradlew aiEvalCompare` baseline vs fresh deterministic report: no hard-invariant regression (exit 0).
- `./gradlew aiEvalLive` without opt-in variables: BUILD SUCCESSFUL, live test skipped (no key, no cost).
- The committed baseline was refreshed from one JVM and matched by the next run on a fresh Gradle daemon (reproducible).
- `deterministic.json` / `deterministic.md` in this directory are the report of the final run (31 cases, dataset 1.3.0, all delivered-layer invariants 0 failures, 31/31 pinned behaviors).

Not run: live provider evaluation (needs an API key and spends tokens).
