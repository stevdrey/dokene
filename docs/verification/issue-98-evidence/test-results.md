# Issue 98 test evidence

Command: `cd backend && ./gradlew test` (JDK 26, Gradle 9.8.0, Testcontainers postgres:17-alpine). Result: BUILD SUCCESSFUL.

Full suite: 1004 tests, 0 skipped, 0 failed.

Evaluation suites:

| Suite | Tests | Skipped | Failed |
|---|--:|--:|--:|
| AiEvalDatasetTest | 5 | 0 | 0 |
| AiEvalDeterministicIntegrationTest | 1 | 0 | 0 |
| EvalReportTest | 5 | 0 | 0 |
| InvariantCheckerTest | 9 | 0 | 0 |

Additional checks run:

- `./gradlew aiEvalCompare` baseline vs fresh deterministic report: no hard-invariant regression (exit 0).
- `./gradlew aiEvalLive` without opt-in variables: BUILD SUCCESSFUL, live test skipped (no key, no cost).
- Deterministic report reproduced identically across two fresh JVMs (daemon stopped between runs).
- `deterministic.json` / `deterministic.md` in this directory are the report of the final run (26 cases, all delivered-layer invariants 0 failures).

Not run: live provider evaluation (needs an API key and spends tokens).
