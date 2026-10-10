---
name: create-pull-request
description: Use when creating, drafting, publishing, or updating a GitHub pull request. Write evidence-based PR titles prefixed with [Issue-<number>] and descriptions following Dokene PR #161's What, Why, Changes, How to test / verify, Risk & rollout, and Notes for reviewers structure.
---

# Create Pull Request

## When to use

Apply this skill whenever an agent creates a PR, prepares a PR description, or updates the title/body of an existing PR. Combine it with `agent-task-workflow` and any implementation skills relevant to the change. Follow `AGENTS.md` and `.agents/README.md`.

Use [PR #161](https://github.com/stevdrey/dokene/pull/161) as the writing-style reference, not as a source of facts for another PR.

## Gather evidence before writing

1. Identify the actual GitHub Issue(s), their acceptance criteria, and the primary Issue number.
2. Compare the PR head against its intended base; inspect changed files and important commits. Never infer implementation solely from the Issue or branch name.
3. Reuse task context and verification results already established. Check docs, ADRs, specs, security invariants, and migration details relevant to the diff.
4. Record which checks actually ran, their outcomes, and meaningful outstanding checks or risks.
5. If no primary GitHub Issue is identifiable, do not invent a number. Ask for the Issue reference when feasible; otherwise explicitly flag the missing reference in the PR draft and do not fabricate a compliant title.

## PR title / summary

**Required:** `[Issue-<number>] <concise imperative summary>`

- `<number>` is the numeric ID of the primary linked GitHub Issue, without `#`, braces, or placeholders in the final title.
- Preserve useful existing context such as `[Messaging][Phase 3]` after the required prefix, when relevant.
- Summarize the actual change, not only the Issue name. Avoid vague titles and duplicating the prefix.
- Example: `[Issue-123] [Messaging][Phase 3] Add the outbound message state machine`.
- If multiple Issues are addressed, select one primary Issue for the prefix; link the others in the body. Do not combine numbers inside the prefix.

## PR body template

Use these headings **in this order**, with concrete evidence and no empty or invented claims:

```markdown
## What

<Concise outcome, scope, and explicit boundaries/non-goals.>

## Why

<Problem being solved, primary linked Issue, acceptance criteria, and relevant ADR/spec context.>

## Changes

- **<Area or module>:** <Specific implementation and behavior change>.
- **<Another area>:** <Specific implementation and behavior change>.
- **Documentation:** <Relevant documentation changes, if any>.

## How to test / verify

- `<exact command>` — **Passed**: <what it validates>.
- `<exact command>` — **Failed**: <failure and significance, when applicable>.
- **Not run:** <required check, reason, and impact, if applicable>.
- **Manual verification:** <steps and actual outcome, if applicable>.

## Risk & rollout

<Risks, compatibility, tenant/security implications, database migrations, release flags, rollout, and rollback strategy when applicable. Say "Not applicable" with a reason for genuinely inapplicable topics.>

## Notes for reviewers

<High-value review focus, deliberate design trade-offs, known limitations, and follow-up work.>

Closes #<primary-issue-number>
```

### Writing guidance

- Keep `What` and `Why` distinct: outcome versus motivation.
- Group `Changes` by subsystem (schema, domain, application, API, UI, tests, docs) as appropriate; describe observable behavior and important contracts, not a raw file dump.
- In `How to test / verify`, provide runnable commands relative to a stated working directory and list honest results. Do not label tests as passed unless they actually passed; distinguish CI status from local checks.
- In `Risk & rollout`, discuss migration/backward compatibility, rollback, security, tenant isolation, deployment toggles, and operational implications **when relevant**. Do not claim a migration is reversible if it has not been validated.
- In `Notes for reviewers`, call attention to important architectural or security decisions, unusual edge cases, limitations, and dependencies.
- Use precise technical English, Markdown headings, and actionable content. Avoid generic filler, marketing language, and speculation stated as fact.
- Include `Closes #N` **only** when this PR is intended to fully close that Issue on merge. Otherwise use `Refs #N`, `Part of #N`, or another non-closing reference, including for additional Issues where appropriate.

## Publication checklist

1. Confirm title begins with exactly `[Issue-<primary-number>]`.
2. Confirm all six required headings appear in order.
3. Confirm implementation claims match the diff and related documentation.
4. Confirm verification claims match executed commands / observed CI, explicitly noting limitations.
5. Confirm the Issue link and closing keywords have the intended effect.
6. Confirm risks, migrations, and unresolved blockers are visible instead of buried.
7. Create or update the PR only after these checks; preserve valid prior discussion when updating a PR.
8. After publishing, report the PR URL and any important verification limitations.

Never modify unrelated PRs or fabricate Issue identifiers, test execution, approvals, or rollout assurances.
