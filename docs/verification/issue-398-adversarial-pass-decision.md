# Issue #398 adversarial-pass data and revision decision

Status: follows the [Issue #376 decision](issue-376-benchmark-decision.md) for [Epic #371](https://github.com/stevdrey/dokene/issues/371). This record collects trigger and cost data for the optional adversarial pass of `independent-implementation-verification` on five real Issues, and decides the three revisions #376 proposed. It changes no application behavior.

The record is sanitized: counts, statuses, test names, and redacted probe output only. All probe data was invented and every probe ran on local services. No live provider, message send, or production data was involved.

## Decision

The pass **stays optional and risk-triggered; it is not adopted as a default.** It ran on all five triggered Issues, cost about 6 tool calls per Issue, and produced no finding of its own: no defect the ordinary hunt missed, and no false-positive blocker. The adoption bar in #398 (at least one confirmed defect the ordinary hunt missed, with no increase in false-positive blockers) is not met.

| Revision proposed in #376 | Decision | Evidence |
| --- | --- | --- |
| 1. Add "Idempotency and API contracts" (keys, fingerprints, cursors, status codes) to the trigger table | **Not adopted** | The row would have matched 1 of the 5 Issues (#324, an idempotent migration-job rerun, not an HTTP contract). In none of the five did the pass find a defect, and in #376 the ordinary hunt caught both idempotency and pagination seeds without the pass. Nothing shows the row would add detection. |
| 2. Make the "adversarial pass not run: no trigger" record checkable | **Adopted as a warning only** | 6 of the 7 Issue PRs merged since the pass shipped do not say whether it ran, and in #376 2 of the 3 pilot verdicts omitted the record the guidance asks for. `verification-verdict.mjs` now prints a `WARNING` when a verdict has neither the line nor a pass check. The warning changes no result or exit code, so nothing becomes compulsory. |
| 3. Measure blast-radius analysis on an implementer run | **Not measured; stays a pilot** | It needs two full implementations of one high-risk Issue, one with the skill. That is an implementer study, outside a verifier replay; see [Not measured](#not-measured). No guidance changes. |

## Why replay merged Issues

#398 asked for "the next five Issues whose diff hits an adversarial-pass trigger". The forward data does not exist yet:

- No Issue has merged since #376.
- The seven Issue PRs merged since the pass shipped ([#379](https://github.com/stevdrey/dokene/pull/379), 2026-11-10) never ran it. Two hit a trigger: [#380](https://github.com/stevdrey/dokene/pull/380) (Issue #311, Authorization and OIDC) and [#368](https://github.com/stevdrey/dokene/pull/368) (Issue #314, OIDC realm). Neither recorded an independent verdict at all.

| PR | Issue | Trigger hit (this record's reading) | Independent verdict | Says whether the pass ran |
| --- | --- | --- | --- | --- |
| #385 | #319 container hardening | none | yes | yes, "not run: no trigger" |
| #369 | #309 Actions pinning | none | yes | no |
| #380 | #311 anonymous sessions | Authorization, External integrations (OIDC) | no | no |
| #381 | #375 operator journey | none | yes | no |
| #392 | #323 build-input pinning | none | no | no |
| #368 | #314 identity provider realm | External integrations (OIDC) | no | no |
| #383 | #382 BFF login helper | none (local QA scripts) | no | no |

So, with the owner's agreement, the data comes from replays instead. A fresh verifier ran the current guidance (`.agents/` at `765719a`) on the exact merged change of five recent real Issues whose diffs hit a trigger. Each candidate is the squash-merge commit, and its base is that commit's parent.

## Design

Each verifier received [the same prompt](issue-398-evidence/verifier-prompt-template.txt) and worked in two phases:

- **Phase A** was the ordinary verification without the pass. The verifier wrote its findings to `phase-a.json` before going on.
- **Phase B** was the adversarial pass. Its findings are numbered `A1…`, its checks start with `probe:`, `base control:`, or `negative control:`, and a pass finding that only restates a phase A finding is not raised.

A finding "only the pass produced" is therefore an `A` finding that phase A did not already have.

| Issue | Candidate (head / tree) | Base | Triggers the implementer handoff named |
| --- | --- | --- | --- |
| [#315](https://github.com/stevdrey/dokene/issues/315) narrow membership privileges | `d62a94d` / `878aa8df` | `bdbd9e5` | Migrations, Tenant isolation |
| [#322](https://github.com/stevdrey/dokene/issues/322) OpenAI endpoint validation | `44b78c3` / `5a283077` | `f0b5062` | External integrations |
| [#324](https://github.com/stevdrey/dokene/issues/324) signing-key retirement | `01c8490` / `c99c6753` | `44b78c3` | Tenant isolation, Concurrency |
| [#325](https://github.com/stevdrey/dokene/issues/325) membership-change authority | `38deb90` / `09532008` | `01c8490` | Authorization, Concurrency |
| [#311](https://github.com/stevdrey/dokene/issues/311) anonymous session creation | `341464a` / `859d8a39` | `016695e` | Authorization, External integrations (OIDC) |

The selection covers every trigger row except PII and messaging idempotency, and skips #326, whose code the #374 exercise already used. The budget was 45 tool calls per verifier, about 15 for the pass, and Gradle only with `--tests` filters (Testcontainers allowed). There was no full build, no Compose stack, and no network. The setup was Linux with JDK 26.0.2 Temurin through the Gradle Wrapper, one verifier at a time, all on 2026-11-13.

## Results

| Issue | Verdict | Triggers the verifier found | Pass ran | Pass work | Phase A / pass tool calls (self-reported) | Harness total: tokens, tool calls, duration | Findings |
| --- | --- | --- | --- | --- | --- | --- | --- |
| #315 | `pass` | Migrations, Tenant isolation | yes | 5 hypotheses; 1 probe class with 2 probes; a control showing the probe fails on the base | 14 / 5 | 121,652, 22, 541 s | F1 optional, phase A (RLS recipe not extended) |
| #322 | `pass` | External integrations | yes | URI/OkHttp parser-differential probe with 25 values; negative control; base control not run (new class) | 7 / 7 | 108,206, 14, 204 s | F1, F2 optional, phase A (IP-literal Graph hosts now rejected; loopback accepted in every profile) |
| #324 | `pass` | Tenant isolation, Concurrency, **Migrations** (handoff missed it) | yes | interleaved-jobs probe with negative control; zero-grace probe without control; 2 hypotheses left unexamined | 14 / 4 | 127,770, 18, 403 s | F1 optional, phase A (a non-zero grace shorter than the documented 130 s is accepted; the documented range is `0s`–`7d`, so this is advice, not a contract); F2 optional, phase A (record revision) |
| #325 | `pass` | Authorization, Concurrency, **Tenant isolation** (handoff missed it) | yes | 5 hypotheses; 2 negative controls (recheck removed: 8 of 27 tests fail; locks removed: 7 of 32 fail); 2 hypotheses by code reading only | 14 / 6 | 125,008, 23, 321 s | F1 optional, phase A (record revision); F2 optional, phase A (concurrent role changes now both succeed instead of a 409, documented in ADR 0031) |
| #311 | `pass` | Authorization, External integrations (OIDC) | yes | 5 hypotheses; path-variant probe against the login-start limit; negative control (4 of 5 tests fail); base control not run (new code) | 12 / 6 | 131,281, 18, 397 s | F1 optional, phase A (record names pre-squash revisions) |

| Total | Pass ran | Pass-only findings | Pass-only confirmed defects | False-positive blockers | Pass tool calls (self-reported) | Harness tokens | Harness tool calls |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 5 Issues | 5 of 5 | 0 | 0 | 0 (no blocker at all) | 28 (about 6 per Issue) | 613,917 | 95 |

Harness totals are the per-subagent figures the agent harness reported. The phase split is the verifier's own count. Some verifiers also counted the calls that wrote and validated the verdict, outside both phases (2 on #315 and #325). With those added, each self-count is within one call of the harness total: 21 against 22, 14 against 14, 18 against 18, 22 against 23, and 18 against 18. Tokens per phase were not observable, so the pass's token cost cannot be separated from the verifier's; #376 measured it once at about 15,700 tokens (+18%).

Every verdict validated with `verification-verdict.mjs` against its candidate (exit `0`). The four kept verdicts are in [`verdicts/`](issue-398-evidence/verdicts/), and the probe sources and outputs in [`probes/`](issue-398-evidence/probes/). The #315 verdict file was lost when the scratch directory was cleared by a session restart, after it had validated with exit `0`; its row comes from the harness report and the verifier's report.

### Observations

- **Detection:** the pass added none. Every finding came from phase A, and all eight were optional. Across #376 and #398 the pass has run 6 times (K3 and these five) and produced no finding of its own.
- **Assurance:** the pass did add evidence that phase A lacked. On four of the five Issues (#311, #322, #324, #325) it ran negative controls showing that the shipped tests or its probe fail when the protection is removed, and it added probes for interleavings and parser differences that no shipped test covered. That is stronger evidence of a pass, not a found defect, so it does not meet the adoption bar.
- **Cost:** 4 to 7 tool calls per Issue, within the budget of about 15, and no run exceeded the 45-call verifier budget.
- **Trigger accuracy:** the verifier confirmed every trigger the handoff named and added one the handoff missed on two Issues: Migrations on #324 and Tenant isolation on #325. The table was never ambiguous enough to skip the pass on a triggered diff.
- **Idempotency and API contracts row:** verifiers judged it would match only #324 (idempotent job reruns). #325 changed a status-code outcome (a concurrent loser now succeeds instead of getting `409`), yet its verifier judged the row would not match, because no contract or mapping file changed. The ordinary hunt found and classified that change anyway (F2), as it did the #376 seeds.
- **Recurring record gap:** in 3 of 5 replays (#324, #325, #311) the Issue's verification record does not name the final tested revision by a reachable hash; #311 names pre-squash branch commits. Each was rated optional. It is a records issue for the `create-pull-request` evidence, not an adversarial-pass matter.
- **Process gap, out of scope:** two real triggered Issues (#311, #314) merged without any independent verdict. The #311 replay passed, so no defect resulted, but the gap is in using the verification skill at all, which no trigger-table change can close.

## Changes in this PR

- `verification-verdict.mjs` prints `WARNING: the verdict does not say whether the adversarial pass ran …` when a verdict has no check whose summary starts with `probe:`, `base control:`, or `negative control:` and no limitation saying "adversarial pass not run". The computed result and the exit code are unchanged. On the stored verdicts, it flags all three #376 baseline verdicts and the K1 and K2 pilot verdicts, and it accepts the K3 pilot verdict and the four replay verdicts.
- `verification-verdict.test.mjs` covers the warning for an unrecorded, a skipped, a run, and a failing verdict, and shows that a role word in the middle of a summary does not count as a run.
- `adversarial-review.md` names the warning under *When to run* and links this record.

No trigger row, budget, blocking bar, or default changes.

## Rollback

Remove `adversarialPassRecord` and its warning from `verification-verdict.mjs`, its test, and the sentence in `adversarial-review.md`. Nothing else depends on it, and no gate reads the warning.

## Reproduce

From the repository root, with JDK 26 active and Docker available, for each Issue `<n>` and its merge commit `<head>` from the design table:

```bash
REPLAY=<scratch directory outside the repository>
git archive 765719a .agents | tar -C "$REPLAY/guidance" -xf -
git worktree add --detach "$REPLAY/cand-<n>" <head>
(cd "$REPLAY/cand-<n>" && node "$REPLAY/guidance/.agents/scripts/verification-verdict.mjs" --identity --base HEAD^)
```

Fill the [prompt template](issue-398-evidence/verifier-prompt-template.txt): `{ISSUE}` is `<n>`, `{CAND}` the candidate copy, `{GUIDE}` `$REPLAY/guidance`, `{BASE}` the parent commit, `{IDENTITY}` the identity JSON, `{ISSUE_BODY}` the Issue text, `{TRIGGERS}` the design-table triggers, and `{RUN}` `$REPLAY/runs/<n>`. Start a fresh subagent with no other context. To rerun a stored probe, copy its `*.java.txt` into `backend/src/test/java/` of a candidate copy under its package path, drop the `.txt`, and run it with `./gradlew test --tests '<ClassName>'`; apply a `negative-control.patch` to a second copy for the negative control. Outputs saved as `.log` by the verifiers are stored as `*-output.txt`, because the repository ignores `*.log`.

## Not measured

- **Forward data:** no Issue merged after #376 is in the sample. The replays use the current guidance on merged code. That is the same verifier procedure, but an implementer who knew the pass would run might have written different tests.
- **Bias:** each verifier could read the repository history and knew the change had merged. The prompt told it to judge the change as a fresh candidate.
- **Pass tokens:** only per-subagent totals were observable.
- **Variance:** one run per Issue.
- **Full suites:** no full `check` or backend suite ran, because of host memory; targeted suites and probes did.
- **Revision 3:** blast-radius analysis is an implementer step. Measuring it needs one high-risk Issue implemented twice, which is a separate study of about two implementation loops. It stays a pilot with no new evidence, as #376 left it.
- **Human review time:** not measured.
