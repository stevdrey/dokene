---
name: issue-implementation-loop
description: Use when asked to implement a GitHub Issue by number end to end. Resolve the Issue and its context, ask about material gaps, implement, obtain an independent verdict for the exact candidate, fix and re-verify until it passes or the loop must stop, then commit, push, and open an evidence-backed PR. Never merges.
---

# Issue Implementation Loop

## When to use

Apply this skill when a human asks an agent to implement a GitHub Issue identified by its number, for example "Implement Issue #350" or "implement https://github.com/stevdrey/dokene/issues/350 on branch `350-…`".

This skill coordinates other skills and does not restate their rules:

- `agent-task-workflow` for scoping, execution, review, and stop conditions;
- the implementation skills that apply to the change (`backend-java-spring`, `frontend-react-typescript`);
- `independent-implementation-verification` for the handoff, the verifier role, and the verdict schema;
- `create-pull-request` for the PR title and body.

`AGENTS.md`, `.agents/README.md`, accepted ADRs, and security invariants apply throughout, in the precedence order of `AGENTS.md`. This skill authorizes no more than the human and repository already allow: no bypassed protections or security controls, no live external side effects, and **no merge**.

## Parameters

| Parameter | Required | Default | Meaning |
| --- | --- | --- | --- |
| `issue` | yes | — | Issue number or URL. |
| `repository` | no | the `origin` remote of the worktree | Repository that owns the Issue. A URL for another repository is a mismatch to report, not to work around. |
| `base` | no | `origin/main` | Ref the PR targets; the parent branch for a stacked branch. Passed as `--base` to every helper. |
| `branch` | no | a new branch per repository convention | Branch to commit and push to. An existing remote branch is tracked, not recreated. |
| `maxRounds` | no | `3` | Independent verification rounds before the loop stops and reports. |
| `draft` | no | `false` | Open the PR as a draft. |

## Loop state

Keep a loop state file **outside the worktree** (a scratch or temporary directory), so it never changes the candidate identity:

```json
{
  "schemaVersion": 1,
  "issue": 350,
  "maxRounds": 3,
  "questions": [
    { "id": "Q1", "text": "…", "material": true, "status": "answered", "answeredAfterRound": 0 },
    { "id": "Q2", "text": "…", "material": true, "status": "open" }
  ],
  "rounds": [
    { "verdict": "verdict-1.json" }
  ]
}
```

`rounds` lists every verdict file in order, relative to the state file. Ask the helper for the next step whenever a phase ends:

```bash
node .agents/scripts/issue-loop.mjs --state <state file> --base <ref> [--commit <rev to push>]
```

It prints `{ "action", "reason", "rounds", "maxRounds" }`. Verdict gating reuses the rules of `verification-verdict.mjs`, so the helper only decides what the loop does next:

| Action | Exit | When | Next step |
| --- | --- | --- | --- |
| `ask` | 1 | A material question is open. | Ask the human and wait; continue independent work meanwhile. |
| `verify` | 1 | No round yet, the candidate changed after the last verdict (`STALE`), or a material question was answered after the last round. | Start a fresh independent verifier. |
| `fix` | 1 | The latest verdict is `fail` for the current candidate, or the worktree is identical to an earlier failed candidate, and rounds remain. | Fix the blockers, then verify. |
| `commit` | 1 | The latest verdict passes for the worktree: run without `--commit`, or the `--commit` revision does not hold the verified content. | Commit exactly the verified content, then ask again with `--commit`. |
| `publish` | 0 | With `--commit` only: the latest verdict passes for that commit. | Publish. |
| `stop` | 3 | The verdict is `blocked` (including a non-independent verifier), invalid, or inconsistent; any round's verdict is invalid or for another Issue; a round re-verified an unchanged failed candidate; or the round budget is spent or exceeded. | Report; do not publish as verified. After a `blocked` stop, the same candidate may be re-verified once the missing capability is legitimately obtained. |

Exit `2` is an unreadable state or verdict file, a usage error, or a Git error such as an unknown `--base` or `--commit` ref: fix the input, do not treat it as a decision.

## Procedure

### 1. Resolve the Issue and its context

1. Confirm the repository: `gh repo view --json nameWithOwner` in the worktree, compared with the Issue's repository.
2. Read the Issue with one scoped query, `gh issue view <n> --json title,body,labels,comments,state`, then the Issues and epics it links or is part of.
3. Read the repository instructions, ADRs, security documentation, source, and tests relevant to the Issue, per `agent-task-workflow`.
4. Extract the acceptance criteria verbatim as `AC1…ACn`. When the Issue has no checklist, derive them only from explicit requirements and say so.
5. Report anything inaccessible (a private linked Issue, a missing document) or conflicting (Issue versus ADR, Issue versus epic) to the human rather than resolving it silently. Precedence in `AGENTS.md` decides only what it covers.

### 2. Clarify

Record every material gap or ambiguity as a question in the loop state. A question is material when its answer could change behavior, contracts, security, data, or the acceptance criteria; ask the human and wait for the answer before publishing. Non-material questions are recorded with `material: false` and resolved by repository convention, noted in the PR.

Never invent requirements. While a question is open, continue work it does not affect. Mark a question `answered` only with the human's answer, record `answeredAfterRound` as the number of rounds in the state at that moment, and update the criteria it changes. A material answer after a round requires a fresh verification.

### 3. Implement

Check out or create the branch, then implement the scoped change under `agent-task-workflow` and the applicable implementation skills: preserve unrelated work, respect the PR size guard in `.agents/README.md`, and run the narrowest relevant checks. Record the commands and results as the implementer's evidence.

### 4. Verify independently

1. Compute the candidate identity: `node .agents/scripts/verification-verdict.mjs --identity --base <ref>`.
2. Start a **fresh** independent verifier with the handoff prompt template in `independent-implementation-verification`, passing the Issue context, acceptance criteria, base ref, candidate identity, the implementer's evidence, applicable rules, and, for a re-verification, the previous verdict and what changed.
3. Have the verdict written outside the worktree, next to the loop state, and append it to `rounds`.

If no independent agent can be started, the verdict is `blocked` with `verifier.independent: false`. Never verify in the implementing context and call it independent.

### 5. Correct and re-verify

On `fix`, correct only the blockers: failed criteria, open blocker findings, and failed required checks. Optional findings never start another round; list them in the PR. Every correction is a new candidate and needs a fresh verifier. Stop, and report the remaining blocker, when the helper says `stop`, when the same blocker recurs without progress, or when required evidence cannot be obtained legitimately. Never repeat an unchanged failed attempt.

### 6. Publish

Only after the helper returns `commit` for a passing worktree candidate:

1. Stage and commit exactly the verified content (the identity is content-addressed, so the commit keeps the candidate).
2. Run `node .agents/scripts/issue-loop.mjs --state <file> --base <ref> --commit HEAD` and continue only on `publish`. On `commit`, the commit lacks some of the verified content: commit it and check again. On `verify`, the commit holds another candidate and needs a fresh verdict.
3. Push once to the branch.
4. Create the PR with `create-pull-request`. In addition to its template, include:
   - the Issue reference (`Closes #<n>` only when the PR fully closes it; `Part of #<epic>` for an epic);
   - a requirement-to-evidence table: each `AC`, its verdict result, and the checks that support it;
   - the actual commands and results, with each check's kind (`automated`, `manual`, `simulated`, `live`) and `not-run` checks;
   - the edge cases the verifier exercised, the test environment, and the limitations;
   - the candidate identity the verdict covers.

   Never claim a check ran when it did not, never describe a pass as proof of no defects, and redact secrets and personal data.
5. Report the PR URL and the limitations to the human. Do not merge, enable auto-merge, or approve.

Any change after the pass, including a review fix, makes the verdict stale: run steps 4–6 again for the changed candidate.

## Termination

The loop ends in exactly one of these states:

- **published** — a passing verdict covers the pushed commit and the PR is open;
- **waiting** — a material question is open; the human was asked;
- **blocked** — verification or a required check cannot run, the verifier was not independent, a verdict was untrustworthy, the same failed candidate was retried, or the round budget is spent. Report what was verified, what was not, and why; push or open a PR only if the human asks, and then not as verified.

## Examples

**Success.** Issue with `AC1–AC3`, no open questions. Implement, verify; round 1 passes. Commit, `--commit HEAD` returns `publish`, push, open the PR with the evidence table.

**Clarification.** The Issue does not say which roles may call a new endpoint. Record `Q1` as material, ask, and implement the parts that do not depend on it. The helper returns `ask` until `Q1` is answered, then `verify`.

**Fix and re-review.** Round 1 fails `AC2` with an open blocker (empty input accepted). Fix it; the helper returns `verify` for the changed candidate; a fresh verifier passes round 2; publish.

**Stale verdict.** Round 1 passes, then a review comment leads to an edit. The helper returns `verify` (`STALE`), so a fresh verdict is needed before pushing the edit as verified.

**Blocked.** The required integration test needs Docker, which is unavailable. The verifier marks the criterion `blocked`; the helper returns `stop`. Report the missing capability; do not publish as verified.

The scenarios are reproduced by `node --test .agents/scripts/issue-loop.test.mjs`.
