# GitHub Copilot Code Review Instructions

These instructions are specific to GitHub Copilot code review. The canonical provider-neutral guidance remains `AGENTS.md` and `.agents/`.

When reviewing a pull request:

- Apply `AGENTS.md`, `.agents/README.md`, and the review/completion rules in `.agents/skills/agent-task-workflow/SKILL.md`.
- Review the requested change against its stated goal and acceptance criteria. Do not reward or request unrelated scope expansion.
- Prioritize findings that can materially affect correctness, security, tenant isolation, data integrity, API compatibility, migration safety, concurrency, observability/privacy, or merge safety.
- Treat style-only, speculative, preference-based, or non-goal refactors as optional unless they create a concrete maintainability or correctness risk.
- Make every actionable comment specific: identify the affected behavior, the concrete failure/risk, and the smallest reasonable direction for correction.
- Do not repeat a finding that is already resolved by the current head revision or demonstrably covered by repository evidence.
- Respect accepted ADRs and documented intentional patterns. If implementation and durable documentation disagree, flag the mismatch rather than assuming either side is correct.
- Check that behavioral changes have tests appropriate to the risk, including negative/security cases where relevant. Do not demand duplicate tests that provide no additional coverage.
- For backend changes, apply `.agents/skills/backend-java-spring/SKILL.md`.
- For frontend changes, apply `.agents/skills/frontend-react-typescript/SKILL.md`.
- For changes spanning backend and frontend, review the contract from both producer and consumer perspectives.
- Never recommend weakening authentication, authorization, tenant isolation, validation, sandboxing, secret handling, repository protections, or other trust boundaries merely to simplify implementation.

## Review convergence

A review is ready to converge when the explicit acceptance criteria are satisfied, applicable verification passes, and no unresolved material blocker remains.

If the remaining observations are optional, stylistic, speculative, or already resolved, label them accordingly instead of treating them as merge blockers or restarting an implementation/review cycle.