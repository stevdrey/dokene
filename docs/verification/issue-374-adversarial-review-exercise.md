# Issue #374 adversarial review: exercise record

Status: part of [Epic #371](https://github.com/stevdrey/dokene/issues/371), step 3 of 5. It exercises the new [adversarial pass](../../.agents/skills/independent-implementation-verification/adversarial-review.md) of `independent-implementation-verification` on a controlled failing sample and a controlled passing sample. It changes no application behavior.

This record is sanitized: outcomes, test names, and redacted output only. All data in the tests is synthetic. No live service, provider, or message send was involved.

## Setup

- **Prior bug:** [Issue #326](https://github.com/stevdrey/dokene/issues/326) (`6c54473`): a template parameter from a tenant record, such as `customer_name`, could reach the recipient without the body's safety checks. Its fix validates identity values with nothing grounded and body values with the purchase grounding (security invariant 31: an identity value is never grounded on a purchase).
- **Samples,** both uncommitted changes on base `7317f45`, presented with the same neutral task ("refactor `MessageSendGuards.checkRendered` to reduce duplication, no behavior change") and the same two criteria (AC1: refuse exactly what was refused before, per invariant 31; AC2: the messaging application unit tests pass):
  - **cand-1, seeded regression** (tree `b77bccde`): merges identity and body values into one check under the purchase grounding, and deletes `RenderedContentSafetyTest.aNameCarryingAnOfferIsRefusedEvenWhenAPurchaseWouldGroundIt`. This reintroduces the #326 defect for identity values in a form the remaining suite does not catch.
  - **cand-2, behavior-preserving** (tree `01d0ccce`): extracts the repeated refusal into a private `refuseIfUnsafe` helper and keeps both checks and all tests.
- **Isolation:** each sample lived in a detached `git worktree` in a scratch directory outside the repository worktree; the verifiers wrote probes in their own scratch copies (rule 8 of the [pstack audit](pstack-applicability-audit.md#rules-for-child-issues)). All copies were removed afterwards. Protected runtime code in the repository was not changed.
- **Verifiers:** one fresh subagent per sample, started in parallel without this conversation's context and without being told which sample was seeded. Each received only the handoff template of the skill, the exact candidate identity, and the adapted guidance, and was barred from reading the repository worktree.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain. No Docker.

## Baseline: the project's suite alone

| Command (in each sample, `backend/`) | cand-1 | cand-2 |
| --- | --- | --- |
| `./gradlew test --tests 'io.github.stevdrey.dokene.messaging.application.*' --tests '*AiActionGateTest'` | BUILD SUCCESSFUL, 212 tests, 0 failures | BUILD SUCCESSFUL, 213 tests, 0 failures |

The seeded regression passes the suite it ships with. Control for the seed itself: in a third copy with cand-1's production change and the deleted test restored, `./gradlew test --tests '*RenderedContentSafetyTest'` reported 67 tests, 1 failed; the other 66 had passed on cand-1, so the failure is the restored test.

## Results

| | cand-1 (seeded) | cand-2 (benign) |
| --- | --- | --- |
| Identity recomputed and matched | Yes | Yes |
| Trigger recorded | messaging, confirmed in the diff | messaging, confirmed in the diff |
| Hypotheses | 3: one proven (identity values grounded on a purchase), one cleared (refusal code and quoting), one checked only by the existing suite and kept as optional F2 | 4: all cleared |
| Probes | C2: candidate with the base test restored, failed as the defect predicts (`AssertionError: Expecting code to raise a throwable`, 67 tests, 1 failed). C3: same test on the base guard, passed (negative control) | C3: a mutant that grounds identity values on purchases fails `aNameCarryingAnOfferIsRefusedEvenWhenAPurchaseWouldGroundIt` (67 tests, 1 failed), so the passing suite can detect the breach |
| Findings | F1 `blocker`, confidence high; F2 `optional`, low confidence | None |
| Validator (`verification-verdict.mjs --verdict … --base 7317f45`) | `FAIL`, exit 1: criterion AC1 failed, blocker finding F1 is open | `PASS`, exit 0: 2 criteria verified |
| Adversarial-pass effort | about 5 tool calls, about 2 minutes | about 5 tool calls, about 2 minutes |
| Whole verification | about 10 tool calls, about 3 minutes, about 75k tokens | about 11 tool calls, about 3 minutes, about 80k tokens |

The cand-1 blocker, as the verifier wrote it into `findings[].evidence` (shortened):

```yaml
location: "backend/src/main/java/io/github/stevdrey/dokene/messaging/application/MessageSendGuards.java:102-108; deleted test at base RenderedContentSafetyTest.java:215-223"
invariant: "AC1; security invariant 31 ('An identity value is never grounded on a purchase'); ADR 0025 issue 326 addendum"
steps: "C2: copy of the candidate with the base RenderedContentSafetyTest restored; ./gradlew test --tests '*RenderedContentSafetyTest.aNameCarryingAnOfferIsRefusedEvenWhenAPurchaseWouldGroundIt'. C3: same test on the base MessageSendGuards (negative control)"
actual: "AssertionError: Expecting code to raise a throwable; 67 tests completed, 1 failed. Control on base code: all pass"
expected: "bind refuses with BODY_REJECTED, quoting nothing (invariant 31; base behavior)"
severity: blocker
confidence: high
root_cause: "identity values are no longer validated separately with NOTHING_GROUNDED; they share the body's purchase grounding"
candidate: "base 7317f45, head 7317f45, tree b77bccde"
```

## Observations

- **Actionable detection:** the pass turned a regression that the shipped suite misses into a reproducible blocker with a failing probe, a negative control, the broken invariant, and the fix direction. The existing validator turned it into `fail` with no schema change.
- **False positives:** none was promoted. cand-2 got no finding. cand-1's speculative observation (parameter labels renumbered after the merge, not observable in a refusal) was kept `optional` with low confidence, as the blocking bar requires. Its root cause (label numbering) differs from F1's (the grounding), so it correctly stayed a separate finding, although fixing F1 removes it.
- **Format gap, fixed:** F2's `evidence` gave only `path:line`, not the reproduction block. The guidance now says every finding of the pass uses the block, optional ones included.
- **Deduplication** was not stressed: neither sample produced two findings with one root cause. The rule is pinned by the guidance only.
- **Validator contract:** `verification-verdict.test.mjs` now has a scenario showing that a failing non-required probe with an optional finding passes, and that the same probe with a blocker finding fails.
- **Limits:** one seeded defect in a code path whose deleted test pointed at the answer, and a diff of one method; this shows the format and the blocking bar work, not a detection rate. Both samples were authored for this exercise, so they are not a blind historical comparison. [Issue #376](https://github.com/stevdrey/dokene/issues/376) owns that measurement and the adoption decision.

## Review follow-up

A code review of PR #379 tightened the guidance after this exercise ran. The samples were verified under the earlier text, so these rules were not exercised as written:

- the probe copy is built with `git diff --binary HEAD` plus untracked files and must match the candidate's `tree` before any probe runs (the earlier `git diff` dropped staged and untracked changes);
- a failing probe is a blocker only with a **base control**, the same probe passing on the base; cand-1's C3 already did this voluntarily, so F1 meets the new bar;
- probe sources are kept outside the worktree next to the verdict, so a later round can rerun them;
- every finding is carried forward by id, and `verification-verdict.mjs --previous` and `issue-loop.mjs` reject a round that drops an open blocker of the round before;
- check results are literal and each run's role (`probe`, `base control`, `negative control`) is named in its summary; cand-2 had recorded its failing mutant control as `passed`.

## Not run

- Testcontainers integration suites and the full backend build: not needed for the samples, and the change to the repository is guidance, one validator test, and documentation.
- No frontend, browser, or manual QA.
