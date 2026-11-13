# Agent Skills

These skills are intentionally provider-neutral and reusable. Use the smallest relevant set for the task.

| Skill | Use when |
| --- | --- |
| `agent-task-workflow` | Scoping implementation work, preparing execution plans, reviewing PRs, or checking completion criteria. |
| `backend-java-spring` | Implementing or reviewing Java 26, Spring Boot, persistence, API, security, integration, or backend tests. |
| `create-pull-request` | Creating, drafting, publishing, or updating PR titles and descriptions, including the required `[Issue-<number>]` prefix. |
| `issue-implementation-loop` | Implementing a GitHub Issue by number end to end: context, clarification, implementation, independent verification loop, and PR creation (never merge). |
| `independent-implementation-verification` | Verifying an Issue implementation through an independent subagent before PR publication, and re-verifying after every correction. |
| `operator-journey-verification` | Pilot, optional: an operator-visible journey with a journey file changes and only the running local stack can observe the effect (real BFF session, CSRF, roles, deployed image). |
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

Before publishing an Issue implementation, load `independent-implementation-verification` and delegate verification of the exact candidate to an independent subagent. Publish only after a passing verdict for that candidate.

To implement an Issue by number, load `issue-implementation-loop`. It composes the skills above and adds the loop state, termination rules, and `.agents/scripts/issue-loop.mjs`, for example:

```text
Implement Issue #350 with .agents/skills/issue-implementation-loop (base origin/main, branch 350-…).
```

When preparing or publishing any pull request, additionally load `create-pull-request` to apply the canonical PR title and description format.

After an implementing PR is complete, load `github-issue-executive-summary` when preparing a stakeholder-facing outcome summary on its Issue. Gather verifiable facts first; do not publish the comment unless explicitly asked.

## Canonical Location

The canonical skill path is `.agents/skills/<skill-name>/SKILL.md`.

Do not mirror or fork these skills into provider-specific directories. If a tool requires a provider-specific discovery file, point that file to `.agents/` instead of copying the skill contents.
