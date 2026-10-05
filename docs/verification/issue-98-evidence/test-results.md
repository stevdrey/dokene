# Issue 98 test evidence

Command: `cd backend && ./gradlew test` (JDK 26, Gradle 9.8.0, Testcontainers postgres:17-alpine). Result: BUILD SUCCESSFUL.

Full suite: 1020 tests, 0 skipped, 0 failed (after the second Codex review follow-up).

Evaluation and gate suites:

| Suite | Tests | Skipped | Failed |
|---|--:|--:|--:|
| DraftSafetyValidatorTest | 38 | 0 | 0 |
| AiEvalDatasetTest | 5 | 0 | 0 |
| AiEvalDeterministicIntegrationTest | 1 | 0 | 0 |
| EvalReportTest | 10 | 0 | 0 |
| InvariantCheckerParityTest | 1 | 0 | 0 |
| InvariantCheckerTest | 15 | 0 | 0 |
| RecordingAiProviderTest | 2 | 0 | 0 |
| DefaultAiActionGateTest | 58 | 0 | 0 |

Additional checks run:

- `./gradlew aiEvalCompare` baseline vs fresh deterministic report: no hard-invariant regression (exit 0).
- `./gradlew aiEvalLive` without opt-in variables: BUILD SUCCESSFUL, live test skipped (no key, no cost).
- The committed baseline was refreshed from one JVM and matched by the next run on a fresh Gradle daemon (reproducible).
- `deterministic.json` / `deterministic.md` in this directory are the report of the final run (29 cases, dataset 1.1.0, all delivered-layer invariants 0 failures, 29/29 pinned behaviors).

Not run: live provider evaluation (needs an API key and spends tokens).
