---
name: independent-implementation-verification
description: Use before publishing a PR for an Issue implementation. Delegate verification of the exact candidate to an independent subagent, which maps each acceptance criterion to evidence, hunts edge-case defects, exercises a safe test environment when feasible, and returns a structured pass, fail, or blocked verdict.
---

# Independent Implementation Verification

## When to use

Apply this skill when an implementing agent believes an Issue's implementation is ready and before it pushes or creates a PR. Verification may happen before or after the local commit: the candidate identity is the content, so committing exactly the verified content keeps a pass valid. Use it again after every correction. Combine it with `agent-task-workflow` and the implementation skills that apply to the change; follow `AGENTS.md` and `.agents/README.md`.

The skill has two roles:

- the **implementing agent** prepares the handoff, starts the verifier, and acts on the verdict;
- the **verifier** is an independent subagent that inspects, tests, and reports. It does not fix the code.

A passing verdict is evidence that the listed criteria were met by the listed candidate under the listed checks. It never proves the absence of all defects; do not describe it that way in PRs or summaries.

## Boundary

The verifier reports to the implementing agent only. Publication is decided by the coordinating workflow (#348) and the `create-pull-request` skill.

The verifier must never:

- edit, commit, stash, reset, or check out files in the candidate worktree;
- push, merge, open or update PRs, or comment on Issues;
- send messages, emails, or webhooks to real recipients, or touch production data or accounts;
- bypass sandboxing, permissions, authorization, tenant isolation, or repository protections to obtain evidence;
- print, store, or quote secrets, tokens, or personal data; redact them in commands and output.

When evidence would require any of these, record it as a limitation and mark the affected criterion `blocked`.

## Independence

Start the verifier as a **fresh** agent without the implementer's conversation, so it judges the code rather than the implementer's narrative. Any provider mechanism that does this is acceptable, for example the Claude Code `Agent` tool or a Codex sub-agent. Give it read access to the repository and the ability to run local checks.

If no independent agent can be started, do not verify in the same context and call it independent. Produce a `blocked` verdict with `verifier.independent: false`; it cannot pass.

## Handoff inputs

The implementing agent provides:

1. **Issue context:** the Issue number, its body, and relevant linked Issues or epics.
2. **Acceptance criteria:** one stable id per criterion (`AC1`, `AC2`, …), taken from the Issue. Do not invent criteria; flag missing or ambiguous ones instead.
3. **Base ref:** the ref the PR will target, `origin/main` by default or the parent branch of a stacked branch. Every identity and validation command in this skill takes it as `--base <ref>`; a command run without it measures against `origin/main` and reports another candidate for a stacked branch.
4. **Candidate identity:** the output of the command below, run in the candidate worktree. `tree` is the Git tree of the candidate's content, including staged, unstaged, and untracked changes that are not ignored; `head` is recorded for traceability. Two candidates are the same when `base` and `tree` match, so a commit of identical content is the same candidate and any edit is a new one.

   ```bash
   node .agents/scripts/verification-verdict.mjs --identity --base <ref>
   ```

5. **Implementer's evidence:** commands run and their results. These are claims for the verifier to check, not facts.
6. **Applicable rules:** `AGENTS.md`, `.agents/README.md`, relevant ADRs, security invariants, and the skills that apply to the change.
7. **For a re-verification:** the previous verdict and what was changed since.

### Handoff prompt template

```text
You are an independent verifier for Issue #<n> in <repository path>. Follow
.agents/skills/independent-implementation-verification/SKILL.md, verifier role.

Base ref: <ref>
Candidate identity (must match `node .agents/scripts/verification-verdict.mjs --identity --base <ref>`):
<identity JSON>

Acceptance criteria:
- AC1: <text>
- AC2: <text>

Linked context: <Issues, epics, ADRs, security docs>
Implementer's claims (verify, do not trust): <commands and results>
Previous verdict and changes since (re-verification only): <verdict path or "none">

Do not modify the worktree, publish, merge, or send messages. Write your verdict JSON to
<path outside the worktree>, validate it with
`node .agents/scripts/verification-verdict.mjs --verdict <path> --base <ref>`,
and return the verdict and the validator output.
```

## Verifier procedure

1. **Confirm the candidate.** Recompute the identity with the handoff's `--base <ref>`. If it differs from the handoff, stop and return `blocked`: the evidence would describe another candidate.
2. **Read independently.** Inspect the diff against the base (`git diff <base>` plus untracked files), the changed code, its callers, and related tests. Read the Issue and linked rules yourself.
3. **Map every criterion.** For each criterion, decide `pass`, `fail`, or `blocked`, citing check ids. A criterion passes only when cited evidence that passed on this candidate shows it.
4. **Hunt for defects.** As applicable to the change, probe boundary values, empty and malformed input, error and failure paths, retries and idempotency, concurrency, backward compatibility of APIs, data and migrations, authorization, tenant isolation, and secret or PII exposure. Prefer realistic cases to speculation.
5. **Run checks.** Run the narrowest relevant automated checks, and the applicable project build when the change crosses boundaries. When feasible and relevant, start a safe, isolated environment (local dev stack, test profile, Testcontainers, a throwaway database) and exercise the implemented behavior. Never point it at production or real third parties.
6. **Record honestly.** For every check, state the exact command or manual steps, the environment, the result, and its kind:
   - `automated` — a test or tool run;
   - `manual` — behavior exercised by hand;
   - `simulated` — stubs, fakes, or recorded fixtures standing in for a real dependency;
   - `live` — a real dependency in a safe test environment.

   A check that was not executed is `not-run`. Never report a check that did not run, and never infer a result from code reading alone.
7. **Report findings.** Each finding has a severity (`blocker` for anything affecting a criterion, correctness, security, tenant isolation, data integrity, API contracts, migration or merge safety; `optional` otherwise), a status, and a concrete reproduction or supporting evidence (command and output, or `file:line`).
8. **Write and validate the verdict** outside the worktree, so the file does not change the candidate identity.

## Verdict

The verifier returns JSON in this shape (schema version 1):

```json
{
  "schemaVersion": 1,
  "issue": 349,
  "candidate": { "base": "<commit id>", "head": "<commit id>", "tree": "<tree id>" },
  "verifier": { "independent": true, "kind": "subagent" },
  "criteria": [
    { "id": "AC1", "text": "…", "result": "pass", "evidence": ["C1"] }
  ],
  "findings": [
    { "id": "F1", "severity": "blocker", "status": "open", "criteria": ["AC1"],
      "summary": "…", "evidence": "<reproduction or file:line>" }
  ],
  "checks": [
    { "id": "C1", "kind": "automated", "required": true, "command": "./gradlew test --tests …",
      "result": "passed", "environment": "local, JDK 26", "summary": "…" }
  ],
  "limitations": ["…"],
  "remainingActions": ["…"],
  "verdict": "fail"
}
```

A check may carry `candidate` when it ran on a different candidate, and must then carry `reusedBecause` (see re-verification).

### Gating rules

The validator computes the verdict and rejects a declared verdict that the evidence does not support:

- **fail** — a criterion failed, a blocker finding is open, or a required check failed;
- **blocked** — the verifier was not independent, no criteria were mapped, a criterion is `blocked`, a passing criterion lacks evidence or cites a check that did not pass, a required check was not run, or the evidence is stale;
- **pass** — none of the above.

`fail` takes precedence over `blocked`, because it is actionable now. Optional findings never block a pass; report them separately so the implementer can decide.

```bash
node .agents/scripts/verification-verdict.mjs --verdict <file> --base <ref>
node .agents/scripts/verification-verdict.mjs --verdict <file> --base <ref> --commit <commit to push>
```

The validator always compares the verdict's candidate with an actual candidate: by default the current worktree, including uncommitted changes, which suits verification before the commit; with `--commit` the tree of that commit alone, which is what a push publishes. Exit `0` is a pass for that candidate, `1` a consistent fail or blocked verdict, and `2` an invalid or inconsistent verdict, or one about another candidate (`STALE`).

Gate publication only on the `--commit` form: a verified change that is left unstaged or out of a partial commit passes the worktree form but not the commit form.

## Acting on the verdict

- **pass:** the implementing agent may proceed to publication under `create-pull-request`, citing the candidate identity, checks, and limitations in *How to test / verify*. Commit exactly the verified content, then run the validator with `--commit <commit to push> --base <ref>` and publish as verified only on exit `0`; any content change voids the pass.
- **fail:** fix the blockers, then re-verify.
- **blocked:** obtain the missing capability legitimately (or ask the human), or stop and report exactly what could not be verified. Do not publish as verified.

Stop and report rather than loop when the same blocker recurs without progress or required evidence cannot be obtained.

## Re-verification after corrections

Every correction produces a new candidate identity, which needs a fresh verdict from an independent verifier. The verifier:

1. re-evaluates every criterion and finding affected by the change, and confirms each prior blocker is resolved with new evidence;
2. reruns checks that cover changed code;
3. may reuse a check from the previous candidate only when its inputs are demonstrably unchanged, recording the old `candidate` and a concrete `reusedBecause` (for example, "no file under `frontend/` changed between the candidates");
4. treats any other old evidence as stale; stale evidence cannot support a pass.
