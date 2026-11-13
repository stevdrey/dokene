# Issue #375 operator journey pilot: proof run

Status: part of [Epic #371](https://github.com/stevdrey/dokene/issues/371), step 4 of 5. It adds the [`operator-journey-verification`](../../.agents/skills/operator-journey-verification/SKILL.md) skill, one journey ([follow-up disposition](../../.agents/skills/operator-journey-verification/journeys/follow-up-disposition.md)), its executable check [`scripts/verify-journey-follow-up-disposition.sh`](../../scripts/verify-journey-follow-up-disposition.sh), and offline tests for the script's helpers. It changes no application behavior.

This record is sanitized: status codes, enum values, counts, and test names only. All data was invented by the script. No WhatsApp, Meta, or AI provider call was made, and no message was created or sent.

## Choice of journey

The follow-up disposition journey was chosen because it crosses the boundaries the Issue names (role isolation, cross-tenant denial, `If-Match`, idempotency, no outbound send) in a few calls, and because it has a system test to compare against. [`FollowUpOperatorJourneySystemTest`](../../backend/src/test/java/io/github/stevdrey/dokene/tenant/security/FollowUpOperatorJourneySystemTest.java) already covers the journey's business rules, but it replaces the security chain with `PurchaseCurlSystemTest.CurlSecurityConfiguration`: CSRF is disabled and the identity comes from a header. The runnable journey goes through the real chain instead: Keycloak login through the BFF, the session cookie, the CSRF token, the seeded memberships, and the backend image built by `./scripts/dev-env.sh`.

**Harness assessment.** The audit asked #375 to assess the tracked browser drivers first. The #122 `cdp.mjs` and `bidi.mjs` drivers pin their campaign's working folder (`/home/stevdrey/qa122/`) and are excluded from CodeQL as local evidence, so they were not reused. The journey uses `curl` and `jq`, which `./scripts/dev-env.sh doctor` already requires, and keeps its single browser step (S21) manual.

## Environment

- Stack: `./scripts/dev-env.sh up --seed` on base `e7fcc67`, with the backend image rebuilt by `./scripts/dev-env.sh restart-backend --yes` before every run below.
- Host: Linux, Docker Compose, loopback only (`http://localhost:8080`, Keycloak on `8081`).
- Actors: the seeded `testoperator` (OPERATOR), `testviewer` (VIEWER), `testuser` (OWNER), plus the isolation tenant `QA Journey Isolation` that the script provisions idempotently.
- Toolchain for the suite comparison: Gradle Wrapper with the Java 26 toolchain; Testcontainers through the local Docker daemon.

## Runs

| Run | Backend | Command | Exit | Steps | First failed step |
| --- | --- | --- | --- | --- | --- |
| Baseline | `e7fcc67` | `./scripts/verify-journey-follow-up-disposition.sh --out <scratch>` | `0` | 20 passed, 0 failed, 1 not-run | none |
| R1, idempotent replay dropped | `e7fcc67` plus the R1 diff | same | `1` | 17 passed, 1 failed, 1 not-run (first script revision, 19 automated steps) | replay: `HTTP 409`, expected `200`, same dismissal id `false` |
| R2, CSRF skipped for dismissals | `e7fcc67` plus the R2 diff | same | `1` | 15 passed, 5 failed, 1 not-run | S6: `HTTP 201`, expected `403` |
| Final baseline | `e7fcc67`, regressions reverted | same | `0` | 20 passed, 0 failed, 1 not-run | none |

Each run took about 4 seconds once the stack was up; a backend rebuild took about 42 seconds. The `not-run` step is S21, the browser check.

### R1: idempotent replay dropped

```diff
-        if (existing.isPresent()) {
+        if (false && existing.isPresent()) { // CONTROLLED REGRESSION, Issue 375 pilot: replay skipped
```

In `FollowUpService.dismiss`, a replay with the same idempotency key no longer returns the original dismissal and instead reaches the version check. R1 ran with the first revision of the script, before the CSRF steps were added; its replay step is S16 in the current numbering, with the same calls and assertion.

The journey failed only at the replay step, with the `X-Request-Id` of the refused request. The existing suite also catches R1: `./gradlew test --tests '*FollowUpServiceTest' --tests '*FollowUpControllerTest' --tests '*FollowUpOperatorJourneySystemTest' --tests '*FollowUpManagementIntegrationTest'` reported 98 tests, 2 failed: `FollowUpManagementIntegrationTest.dismissAdvancesCadenceCycleAndClearsExpiredSnooze` (`FollowUpConflictException`) and `FollowUpOperatorJourneySystemTest` (`expected: 200 but was: 409`). For R1 the journey adds no detection.

### R2: CSRF skipped for one mutating endpoint

```diff
                 .cors(Customizer.withDefaults())
+                // CONTROLLED REGRESSION, Issue 375 pilot: CSRF skipped for one mutating endpoint
+                .csrf(csrf -> csrf.ignoringRequestMatchers(
+                        PathPatternRequestMatcher.pathPattern("/api/customers/*/follow-up-dismissals")))
```

In `TenantSecurityConfiguration`, a request without the CSRF token can dismiss a follow-up. The journey's S6 failed with `HTTP 201` instead of `403`. Because the forged request succeeded, the fixture was no longer due, so S7, S14, S15 and S16 failed as consequences. The procedure tells the reader to start from the first failed step for this reason.

The existing suite does **not** catch R2: the full backend suite, `./gradlew test` in `backend/` on the R2 code, completed in about 3.5 minutes with 2630 tests, 0 failures, 0 skipped (207 result files). Four test classes call the dismissal endpoint: `FollowUpControllerTest` (controller unit test), `AuthorizationBeforeValidationIntegrationTest` (always attaches a valid token with `csrf()`), and `FollowUpCurlSystemTest` and `FollowUpOperatorJourneySystemTest` (test chain with CSRF disabled). None sends a dismissal without a token through the real chain. R2 is the kind of regression the runnable journey exists for: a change to the security chain that only the running stack observes.

## Failure traces

A failed step prints the expected value, the observed status, and the response's `X-Request-Id`, and the journey record keeps the same fields. No body, cookie, token, name, or phone number is printed or stored. The request id correlates with backend logs only when the backend logged that request: none of the refused requests of R1 were logged, because the follow-up handlers map refusals to empty responses without logging them.

## Rerun and cleanup

All runs used the same stack without a reset. Each run made its own fixture customer and idempotency keys and reused the isolation tenant, so no run depended on another. The first development run found that OPERATOR lacks `CUSTOMER_DELETE`, so cleanup failed and the record said so and named the fixture; cleanup now archives through the owner session and returned `204` in every later run. Working files were deleted; the journey records stayed in the scratch directory.

## Offline checks

| Command | Result |
| --- | --- |
| `./scripts/tests/verify-journey-follow-up-disposition-test.sh` | 74 assertions passed: loopback-only targets and login targets (including `localhost.example.com`, `localhost:8080.example.com`, and userinfo forms refused), evidence outside the repository (an `--out` inside it exits `2` and creates nothing), YAML escaping, header parsing, step bookkeeping, a failed eligibility read never counting as "no longer due", a record that cannot be written (or a stale record from another run) failing the run, unexpected queue and message bodies never read as "found" or "zero messages", portable path resolution including symlinks and directory identity, fixture ids, a replay that must return the same real dismissal id, fixture phones confined to the always-valid `+5698` block, YAML control characters, and a static check that the script calls no message send, approval, or creation path |
| `shellcheck -x scripts/verify-journey-follow-up-disposition.sh scripts/tests/verify-journey-follow-up-disposition-test.sh` | No findings |

The `Script tests` workflow runs the offline test on pull requests that change either script.

## Independent verification

Round 1 passed with three optional findings. Two were fixed afterwards: an `--out` inside the repository was refused only after the record had been written there (now refused before anything is created), and the login posted the test password without checking that the form target is loopback, on curl's command line (now checked, and sent through stdin). The third, S13 sending a future version (`ETag + 1`) instead of a once-current one, was fixed later (see below).

Code review of PR #381 found two more gaps, both fixed: an eligibility read that failed (for example `status-500`) counted as "no longer due" in S17, and a journey record that could not be written still let the run exit `0`. S17 now requires `NOT_YET_DUE` or `INELIGIBLE`, and an incomplete record makes the run exit `2`.

A later code review of the script added these fixes:

- **Response shapes:** a queue or message body without an `items` array fails its step; it is no longer read as "found" or as zero messages.
- **Request headers:** the header dump is cleared before each request, so a failed connection cannot reuse the previous ETag or request id.
- **Journey record:** the record is replaced and must carry the current `run_id`, so a stale record from an earlier run is never reported as this run's.
- **Queue walk:** a queue too long to walk is reported as inconclusive, not as "absent".
- **Paths:** `--out` is resolved without GNU `realpath`, so the script also runs on macOS.
- **OIDC callback:** the callback URL is checked for loopback, like the other login steps.
- **S13:** it sends the follow-up policy version that the fixture superseded, an `If-Match` that was once current.
- **S17:** its expected text names both accepted statuses.

Moving the login sequence into a helper shared with `seed-local-qa.sh` and the campaign scripts is follow-up work (stacked PR #383).

A second code review led to these fixes:

- **S21:** the new `--fixture-only` mode builds and keeps a due fixture, so the browser step can actually be performed. It records every step `not-run`; it was exercised live, and its fixture was archived afterwards.
- **Fixture ids:** a missing customer, phone or tenant id now blocks the run instead of making later absence checks pass vacuously.
- **S20:** the step first reads the customer through the same session and tenant (expects `200`) as a positive control. A regression in the message filter itself still needs a message to detect, and the journey creates none on purpose.
- **`.env`:** it is now sourced in a subshell, as `seed-local-qa.sh` does.
- **Inside-repository guard:** it also compares directory identity, which covers case-insensitive file systems.
- **YAML scalars:** all control characters are removed.
- **S6:** it reuses `dismiss()`, which omits the CSRF header when given an empty token.
- **Loopback rule:** it lives only in `is_local_target`.

A later validation comment on PR #381 found that S16 accepted a replay when both dismissal responses lacked an `id` (`null` equal to `null`). S16 now requires HTTP `200` and the same real id in both responses (`same_dismissal`), with offline regression cases for missing ids in either response.

The revalidation of `926454e` found that the random fixture phone (`+569` plus 8 digits from 20000000 to 99999999) could be rejected by `PhoneNormalizer` (libphonenumber 9.0.43, `isValidNumberForRegion` for `CL`), stopping a healthy run at customer creation with HTTP `400`.

- **Rejected blocks:** a scan with the pinned library sampled the whole range and found rejected numbers in the `10`–`19`, `36`, `69`, `70`, `91` and `92` leading blocks. For example, `+56969800000`, which the generator produced from input `49800000`.
- **Fix:** an exhaustive check of `+5698` plus 7 digits accepted all 10,000,000 numbers. The generator now uses only that block (`fixture_phone`). The offline test pins the input `49800000` (now `+56989800000`) and other inputs to the block.
- **Live check:** with a test double that makes the generator's `od` call return `49800000`, the run passed 20 steps with 1 not-run, as did a normal run.

## Observations

- **Detection:** both regressions made the journey fail with an actionable first step. The existing suite caught R1 and missed R2. The journey's value is the real security chain and the deployed image, not the business rules the system test already covers.
- **False positives:** none in the baseline runs. One failure during development was a fixture defect (cleanup permission), not an application defect, and the record reported it as a cleanup failure, not a passed step.
- **Cost:** about 4 seconds per run once the stack is up; starting and seeding the stack, and each backend rebuild, dominate.
- **Limits:** two seeded regressions authored for this pilot, on one journey. This shows the procedure and the check work, not a detection rate. [Issue #376](https://github.com/stevdrey/dokene/issues/376) owns the comparison and the adoption decision.

## Not run

- S21, the browser step: not performed in this pilot. `--fixture-only` now provides a due fixture for it, but no browser harness is tracked for this journey and nobody has run the step, so the UI half of "UI/API sync" is unverified. The API half (queue and eligibility agree for OPERATOR and VIEWER after the disposition, S17 to S19) is automated.
- No live Meta, WhatsApp, or AI provider path; none is part of the journey.
