# Adversarial Pass

An optional, risk-triggered part of the [independent verification](SKILL.md) of one candidate. The verifier, after mapping the criteria, actively tries to break the change and reports only what it can reproduce as a blocker. It is adapted in Dokene's own words from the `interrogate` practice that the [pstack applicability audit](../../../docs/verification/pstack-applicability-audit.md) marked **Adapt**, as part of [Epic #371](https://github.com/stevdrey/dokene/issues/371); [Issue #376](https://github.com/stevdrey/dokene/issues/376) decides from pilot evidence whether it is adopted.

## Boundary

The pass extends the existing gate; it does not replace or loosen it.

- It runs inside the verifier role of `SKILL.md`, by the same fresh, independent subagent, in the same round. It is never run by the implementing agent and never adds a verification round.
- Its output is ordinary `findings[]` and `checks[]` in the verdict, schema version 1, with no new field. `verification-verdict.mjs` remains the only thing that computes the result, and the `issue-implementation-loop` termination rules stay as they are.
- Everything in the skill's *Boundary* section applies: no edits to the candidate worktree, no commits, pushes, merges, PR or Issue comments, no messages to real recipients, no production data, and no bypass of sandboxing, authorization, tenant isolation, or repository protections. A probe that would need any of these is not run; the hypothesis is reported as unverified.
- It approves nothing. A pass with no blocker means only that the attacks listed in the verdict did not break the candidate.

## When to run

Run it, and say which trigger applies, when the candidate's diff touches any of:

| Trigger | Typical Dokene surface |
| --- | --- |
| Tenant isolation | Tenant context, RLS policies, the signed database context, tenant-scoped queries and caches |
| Authorization | Permissions, authorization services, where a decision is made relative to the write |
| PII | Logging, audit metadata, error bodies, prompts, exports, anything that can carry personal data |
| Messaging idempotency | Outbound messages, approval binding, dispatch, outbox, retries, webhook inbox |
| Concurrency | Locks, transactions, isolation levels, version checks, scheduled or parallel work |
| Migrations | Flyway scripts, grants, constraints, data backfills |
| External integrations | Provider adapters, webhooks, OIDC, AI providers, endpoint and credential validation |

The handoff names the triggers the implementer sees, and the verifier adds any it finds in the diff. When no trigger applies, record one line in `limitations` ("adversarial pass not run: no trigger") and skip it. Its absence never blocks a candidate that needs no pass.

## Inputs

- The exact candidate: the base ref and identity from the handoff. Recompute it first, as step 1 of the verifier procedure already requires; a mismatch stops the whole verification as `blocked`.
- The candidate's diff only: `git diff <base>` plus untracked files of that worktree. Attack the code the diff changes or newly reaches. A defect that the diff did not introduce or expose is pre-existing: report it as `optional` with a summary starting `Pre-existing:`, never as a blocker of this candidate.
- Starting points, to be checked rather than trusted: the implementer's claims, a [`blast-radius-analysis`](../blast-radius-analysis/SKILL.md) record when one exists (its `unverified` items and safety assumptions are good attack targets), the security invariants and ADRs the change touches, and, on a re-verification, the previous verdict.

## Procedure

1. **List hypotheses.** Write down concrete ways the change could break a criterion, an invariant, an ADR, or an API contract: a specific input, interleaving, role, tenant, or failure. Each names the invariant or contract it would break. Rank by impact and plausibility, and keep at most five.
2. **Try to falsify each one, cheapest proof first:**
   1. an existing test that already covers it: it clears the hypothesis only if it passes, asserts the outcome the hypothesis is about, and is unchanged by the diff (`git diff <base> -- <test path>` is empty). A test the diff deletes, weakens, or rewrites is itself a target: its base version, run on the candidate, is the natural probe;
   2. a probe test or command run against a disposable copy of the candidate;
   3. a contract or invariant violation shown by code alone, at `path:line`, that needs no runtime assumption, for example a response field the documented contract forbids.
3. **Probe an exact, disposable copy.** Build it outside the candidate worktree so it holds the candidate's whole content, staged, unstaged, and untracked:

   ```bash
   git -C <candidate> worktree add --detach <scratch>/adv HEAD
   git -C <candidate> diff --binary HEAD | git -C <scratch>/adv apply --allow-empty
   (cd <candidate> && git ls-files --others --exclude-standard -z | tar --null -T - -cf -) | tar -C <scratch>/adv -xf -
   ```

   Before probing, run `node .agents/scripts/verification-verdict.mjs --identity --base <ref>` in the copy: its `tree` must equal the candidate's. If it does not, do not probe; record the hypothesis as unverified. Use invented data and local services; assert that no send occurred instead of sending.
4. **Keep the probes.** Write each probe's source and redacted output to `probes/<finding or hypothesis id>/` next to the verdict file, outside the worktree, and cite that path in `steps`, so a later verifier can rerun it. Remove the disposable copies when done; the kept probe directory stays.
5. **Control it.**
   - A probe that **fails** on the candidate is a blocker only after a **base control**: the same probe passes on a disposable copy at `<base>`. If it fails there too, the defect is pre-existing (`Pre-existing:`, optional), or the probe is wrong (diagnose once, then optional, `confidence: low`). When the probed code does not exist on the base, the defect is new by construction: record the base control as `not-run` with that reason; it then does not lower the confidence.
   - A probe that **passes** on the candidate clears the hypothesis only if it can fail: where cheap, show it fails with the protection removed in a disposable copy (a **negative control**). Otherwise say the probe was not controlled.
6. **Classify** each hypothesis with the blocking bar below, and record it.
7. **Deduplicate** before writing the verdict (see below).

## Blocking bar

A finding is `severity: blocker` only with one of:

- a **reproducible failing test or probe** on this candidate that passes on the base (step 5), each run recorded as a check with its exact command and redacted output;
- **command or probe output** that shows the wrong behavior on this candidate and not on the base, for example an HTTP response, a database row, or a log line, recorded the same way;
- a **clearly evidenced contract or invariant violation**: the `path:line` of the candidate code and the `path:line` of the contract, ADR, or numbered invariant it breaks, with no runtime assumption needed to connect them.

A blocker needs `confidence: high` (see below). Everything else is `severity: optional` with status `open`: a hypothesis that could not be run, a failing probe without a base control or resting on an assumption, a passing probe that was not controlled, a plausible race not reproduced within the budget, a defect the base already has, a style or design concern. Its summary starts with `Unverified:`, `Risk:`, or `Pre-existing:` (proven, but not caused by the diff), and its confidence is stated. A hunch is never worded as proven, and an optional finding never starts another verification round.

When a probe is inconclusive (flaky, environment error, timeout), diagnose it once. If still inconclusive, the finding is optional with `confidence: low`, and the check is recorded with the result it actually had.

## Reproduction format

Put the reproduction in the finding's existing `evidence` string, as this block. Every finding of the pass uses it, optional ones included; for a hypothesis that was not run, `steps` says why and `actual` is `not run`.

```yaml
location: "<path:line in the candidate>"
invariant: "<criterion id, contract, ADR section, or security invariant number it breaks>"
steps: "<exact command or probe, and the check id that ran it>"
actual: "<observed result, redacted>"
expected: "<required behavior, and where it is required>"
severity: blocker | optional
confidence: high | medium | low
root_cause: "<one line: the faulty code or missing guard>"
candidate: "<base, head, and tree from --identity>"
```

`confidence` is how sure the verifier is that the finding is real and caused by the diff:

- `high`: a reproduced failure with its base control (or with code that does not exist on the base), or a cited contract or invariant violation that needs no runtime assumption;
- `medium`: a reproduction that rests on a stated assumption, such as a stand-in for a dependency, or that lacks its base control;
- `low`: reasoning alone, or an inconclusive probe.

Only `high` can be a `blocker`. For a cited violation, `steps` names the two cited locations and `actual` quotes the offending code.

Record every run as its own check: `kind` `automated`, `manual`, or `simulated` (never `live`), `required: false`, and `result` the literal outcome of its command (`failed` when the test failed). Its `summary` starts with its role: `probe:` (on the candidate), `base control:` (the probe on the base), or `negative control:` (the protection removed). A probe asserts the expected behavior, so it fails on a defective candidate, and a negative control that fails as intended is still `result: failed`. The blocker finding is what makes the verdict `fail`; a required project check that fails does so on its own. Link the finding to the criteria it affects with `criteria`, and mark those criteria `fail`.

## Deduplication

Several symptoms with one root cause are one finding. Group findings by `root_cause` (the same faulty code or missing guard), keep one finding with the strongest evidence as its reproduction, list the other symptoms in its `summary`, and union their `criteria`. Do not raise a finding for a defect another finding already explains. On a re-verification, a finding the previous verdict recorded keeps its id and is carried forward as [`SKILL.md`](SKILL.md#re-verification-after-corrections) requires; `--previous` rejects a verdict that drops an open blocker. Separate root causes stay separate findings, even in the same file.

## Budget

- One pass per candidate, inside the verifier round. At most five hypotheses and three new probes; existing tests that already cover a hypothesis do not count toward the probe cap.
- About 15 tool calls and 15 minutes of wall-clock time for the pass, beyond the verifier's ordinary checks; reruns of existing tests count toward it. A probe that would exceed it, such as a broad build, is replaced by a narrower one or its hypothesis is recorded as unverified.
- Run the narrowest test or probe that can falsify a hypothesis. The broad suite is the verifier's ordinary check, not part of the pass.
- Stop when the hypotheses are cleared, proven, or recorded as unverified, or when the budget is spent. List hypotheses left unexamined in `limitations`; they do not block.

## Re-verification and convergence

On a re-verification, the pass covers only:

1. each previous adversarial blocker: rerun its kept probe (`probes/<id>/`) on an exact copy of the new candidate and carry the finding into the new verdict's `findings[]` with the same id and refreshed evidence, `status: resolved` only if the rerun passes, otherwise still `open` `blocker`. A probe that cannot be rerun leaves the blocker `open`;
2. code changed since the previous candidate, with the same triggers and budget.

Unchanged code is not attacked again. A proven blocker found incidentally in unchanged code is still reported, because the bar is evidence, not novelty; a new hypothesis there without proof is optional. This keeps the pass from producing a fresh list each round and lets the loop's `maxRounds` and stop rules converge.

## Exercise

The [Issue #374 exercise record](../../../docs/verification/issue-374-adversarial-review-exercise.md) runs this pass on a seeded regression of a prior messaging-safety fix and on a behavior-preserving change of the same code, and shows the resulting findings.
