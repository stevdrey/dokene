# Agent Skills

These skills are intentionally provider-neutral and reusable. Use the smallest relevant set for the task.

| Skill | Use when |
| --- | --- |
| `agent-task-workflow` | Scoping implementation work, preparing execution plans, reviewing PRs, or checking completion criteria. |
| `pr-code-review` | Performing an independent PR review against the linked issue, correctness, security invariants, tests, and merge safety. |
| `backend-java-spring` | Implementing or reviewing Java 26, Spring Boot, persistence, API, security, integration, or backend tests. |
| `frontend-react-typescript` | Implementing or reviewing React, TypeScript, UI state, API integration, accessibility, browser security, or frontend tests. |

## Combination Examples

A backend API feature usually uses:

- `agent-task-workflow`;
- `backend-java-spring`.

A frontend feature usually uses:

- `agent-task-workflow`;
- `frontend-react-typescript`.

A full-stack feature normally uses all three implementation skills.

An independent pull request review normally uses:

- `agent-task-workflow`;
- `pr-code-review`;
- the smallest relevant implementation skill (`backend-java-spring`, `frontend-react-typescript`, or both).

## Canonical Location

The canonical skill path is `.agents/skills/<skill-name>/SKILL.md`.

Do not mirror or fork these skills into provider-specific directories. If a tool requires a provider-specific discovery file, point that file to `.agents/` instead of copying the skill contents.
