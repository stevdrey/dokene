---
name: agent-task-workflow
description: Use when scoping implementation work, preparing execution plans, reviewing pull requests, or validating task completion in a software repository.
---

# Agent Task Workflow

## Context First

Before proposing or implementing a change:

1. inspect the current branch and affected files;
2. read `AGENTS.md` and `.agents/README.md`;
3. read relevant ADRs, architecture, and security documentation;
4. inspect related tests and existing contracts;
5. identify the smallest applicable skill set.

Do not design from issue text alone when repository evidence is available.

## Context Reuse

After the initial discovery pass, treat established repository evidence as task state rather than something to rediscover continuously.

Reuse, while still valid:

- task requirements and acceptance criteria;
- affected modules/files and ownership boundaries;
- relevant ADR/security constraints;
- inspected source and tests;
- known verification commands and their latest outcomes;
- GitHub Issue/PR metadata, comments, review threads, and commit SHAs already fetched during the task.

Re-read or re-fetch only when a relevant file or external state changed, a new ambiguity requires additional evidence, an operation invalidated previous assumptions, or freshness is required for correctness.

Once scope is established, prefer targeted reads over broad repository searches.

## Task Shape

A well-scoped implementation task should identify:

- background and current state;
- goal;
- non-goals;
- affected modules/files;
- architectural and security boundaries;
- API/data-model changes;
- migration/compatibility concerns;
- acceptance criteria;
- verification commands;
- documentation or ADR updates.

## Execution Loop

Use the smallest useful execution loop:

1. establish the current task state and unresolved questions;
2. implement one coherent batch of related changes;
3. run the narrowest verification that can falsify the implementation;
4. record whether that verification passed and what changed afterward;
5. expand to broader integration/build verification only when the affected boundary requires it;
6. perform final repository/remote publication actions after implementation and verification converge.

Do not restart discovery after each edit. Do not repeat a successful verification if no relevant source, test, build, dependency, environment, or external state has changed since it passed.

When several nearby edits are expected, batch them before re-running an expensive integration suite.

## Implementation Rules

- Prefer the smallest coherent change that satisfies the task.
- Do not perform unrelated cleanup unless it is necessary for correctness or explicitly requested.
- Preserve public contracts unless the task intentionally changes them.
- Follow existing dependency direction and module ownership.
- Introduce new abstractions only when they clarify a real boundary or variation point.
- Treat external data, AI output, webhooks, and integration responses as untrusted input.
- Keep privileged side effects behind explicit authorization and policy checks.

## Escalation Handling

Commands requiring network, credentials, Docker, host access, or sandbox escalation should be deliberate.

Before escalating:

1. determine which capability the command actually requires;
2. check whether existing workspace-local evidence or an already-authorized operation is sufficient;
3. batch operations that need the same capability when practical;
4. prefer read-only inspection before remote mutation;
5. avoid retrying a failed escalated command unless a material condition changed.

Do not disable sandboxing, security controls, tenant isolation, authorization, or repository protections to make an agent workflow easier.

If an unavailable capability prevents required verification, state exactly what could not be verified and why instead of repeatedly attempting the same escalation.

## Git and Remote Operations

Prefer local repository state for questions that do not require server freshness. Reuse remote metadata already retrieved during the task.

When publication is required, prefer a deliberate final sequence such as:

1. inspect the intended local diff/status;
2. stage the intended files;
3. commit;
4. push once;
5. create/update the PR or Issue/comment as needed;
6. fetch remote state again only if a new server-side result must be evaluated.

Do not interleave repeated `gh`/GitHub reads and writes throughout implementation when the remote state has not materially changed.

## Review Workflow

A review should check:

- alignment with the stated goal;
- unnecessary scope expansion;
- architectural boundary violations;
- security regressions;
- tenant-isolation implications when applicable;
- error handling and failure modes;
- test quality and missing negative cases;
- backward compatibility;
- observability without sensitive-data leakage;
- documentation and ADR consistency;
- merge safety.

Feedback should be actionable. Distinguish blockers from optional improvements.

Classify findings that materially affect correctness, security, isolation, integrity, compatibility, migration safety, or merge safety as actionable. Treat style-only, speculative, or non-goal improvements as optional unless the task explicitly requires them.

## Review Convergence and Stop Conditions

Do not use repeated review rounds as a goal by themselves.

Stop an implementation/review cycle when:

- acceptance criteria are satisfied;
- required verification passes, or unavailable verification is explicitly documented;
- no unresolved actionable blocker remains;
- required documentation/ADR updates are complete;
- remaining findings are optional, stylistic, speculative, or already resolved.

A fresh review that contains only optional or already-resolved observations should be summarized without triggering another implementation/review loop unless the user explicitly asks to pursue those improvements.

## Completion

Before considering work complete:

- run the relevant tests/builds using the verification-efficiency rules above;
- verify changed API contracts from both consumer and provider perspectives;
- confirm migrations are safe and reversible where practical;
- check that secrets/PII are not introduced into source or logs;
- update documentation when behavior or architecture changed;
- summarize residual risks or follow-up work explicitly;
- avoid a final duplicate verification or remote read solely for reassurance when authoritative evidence is already available.
