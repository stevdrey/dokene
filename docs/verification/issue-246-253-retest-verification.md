# Retest of #246 and #253 (campaign #118)

- Date: 2026-10-21. Code under test: `main` @ `6689d27` (includes PR #257 for #246 and PR #260 for #253).
- Stack: disposable local stack (`dev-env.sh up`), synthetic data only. **No real OpenAI call and no cost**: the AI side used a local fault-injection stub of the Responses API (`scripts/Stub.java`; a `skip` file was added so the first N requests are served normally before failures start).
- Evidence: `issue-246-253-evidence/outputs.txt` (captured output) and `issue-246-253-evidence/scripts/`.

## #246 – database outage: slow 500 instead of fast 503 (RES-09)

| Check | Result |
| --- | --- |
| `GET /api/customers`, `GET /api/follow-up-queue` with PostgreSQL stopped | **503**, `Retry-After: 5`, empty body, ≈3.0 s (was 30 s + generic 500) |
| Valid `POST /api/customers` (CSRF, `Idempotency-Key`) during the outage | **503** in ≈3.1 s, no row persisted |
| AI recommendation during the outage | **503** in ≈3.0 s, no leak |
| Log line | `Database unavailable at the HTTP boundary: exceptionClass=org.postgresql.util.PSQLException` (class only, no message) |
| Recovery | Same session returns 200 on the first attempt after PostgreSQL is healthy; backend container not restarted; valid write returns 201 |
| `GET /api/session` during the outage | 200 (served from the BFF session, needs no database); expected, not a defect |

Note: the first script run reported 3 FAIL lines that were harness errors (the `/api/session` expectation above and a post-recovery write sent without an `Idempotency-Key`, which correctly returned 400). `ret246b.sh` repeats the write checks with the proper helper: 3/3 PASS. Both outputs are kept in `outputs.txt`.

**Verdict: PASS.**

## #253 – `aiEvalLive` green with undelivered scenario families

Stub: the first 2 requests succeed, then 30 consecutive `503` (retryable), then valid output. This reproduces the original situation: some families get no delivered output while the global per-operation ratio still passes.

| Mode | Result |
| --- | --- |
| `DOKENE_AI_EVAL_FAMILY_COVERAGE=warn` (default) | Gradle `BUILD SUCCESSFUL` **and** a console block `[ai-eval] WARNING: AI evaluation coverage INCOMPLETE …` naming each family/operation with `0 of N`, plus one line per undelivered operation with cause `PROVIDER_FAILURE`; report headline `ALL PASS (delivered output only; coverage INCOMPLETE …)`; new "Delivery coverage" section with the per-operation and per-family tables |
| `DOKENE_AI_EVAL_FAMILY_COVERAGE=fail` | `BUILD FAILED`; the assertion message lists the uncovered families and the undelivered cases |
| `DOKENE_AI_EVAL_MIN_SUCCESS_RATIO=0` | Rejected before any provider call (`must be finite and in (0, 1]`), as designed |
| Deterministic suite `./gradlew test --tests '*ai.eval*'` | exit 0 |

JSON report: `summary.families` and `summary.undelivered` present (`PROVIDER_FAILURE` cause), `summary.operations` consistent with the per-family counters.

Harness note: in the `fail` run the stub's request counter was already past the `skip` window, so `rp-01` also failed; this does not change the verdict (the point of that run is the failing assertion).

**Verdict: PASS.**

## Not covered

- Gate-rejection labelling (`GATE_REJECTED`) was exercised only by the deterministic suite, not by this stub run.
- No live-provider timeouts were reproduced; the stub injects `503`, not network timeouts.
