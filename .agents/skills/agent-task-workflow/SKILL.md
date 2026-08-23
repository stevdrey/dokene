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

## Implementation Rules

- Prefer the smallest coherent change that satisfies the task.
- Do not perform unrelated cleanup unless it is necessary for correctness or explicitly requested.
- Preserve public contracts unless the task intentionally changes them.
- Follow existing dependency direction and module ownership.
- Introduce new abstractions only when they clarify a real boundary or variation point.
- Treat external data, AI output, webhooks, and integration responses as untrusted input.
- Keep privileged side effects behind explicit authorization and policy checks.

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

## Completion

Before considering work complete:

- run the relevant tests/builds;
- verify changed API contracts from both consumer and provider perspectives;
- confirm migrations are safe and reversible where practical;
- check that secrets/PII are not introduced into source or logs;
- update documentation when behavior or architecture changed;
- summarize residual risks or follow-up work explicitly.
