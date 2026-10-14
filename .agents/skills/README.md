# Agent Skills

These skills are intentionally provider-neutral and reusable. Use the smallest relevant set for the task.

| Skill | Use when |
| --- | --- |
| `agent-task-workflow` | Scoping implementation work, preparing execution plans, reviewing PRs, or checking completion criteria. |
| `backend-java-spring` | Implementing or reviewing Java 26, Spring Boot, persistence, API, security, integration, or backend tests. |
| `create-pull-request` | Creating, drafting, publishing, or updating PR titles and descriptions, including the required `[Issue-<number>]` prefix. |
| `github-issue-executive-summary` | Summarizing completed Issue outcomes for non-technical stakeholders, optionally posting the summary to the Issue when explicitly requested. |
| `frontend-react-typescript` | Implementing or reviewing React, TypeScript, UI state, API integration, accessibility, browser security, or frontend tests. |

## Combination Examples

A backend API feature usually uses:

- `agent-task-workflow`;
- `backend-java-spring`.

A frontend feature usually uses:

- `agent-task-workflow`;
- `frontend-react-typescript`.

A full-stack feature normally uses all three implementation skills.

When preparing or publishing any pull request, additionally load `create-pull-request` to apply the canonical PR title and description format.

After an implementing PR is complete, load `github-issue-executive-summary` when preparing a stakeholder-facing outcome summary on its Issue. Gather verifiable facts first; do not publish the comment unless explicitly asked.

## Canonical Location

The canonical skill path is `.agents/skills/<skill-name>/SKILL.md`.

Do not mirror or fork these skills into provider-specific directories. If a tool requires a provider-specific discovery file, point that file to `.agents/` instead of copying the skill contents.
