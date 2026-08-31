---
name: pr-code-review
description: Use when reviewing a pull request against its linked issue, repository architecture, security invariants, tests, and intended scope.
---

# Pull Request Code Review

## Goal

Review the pull request as an independent verifier. Determine whether the change is correct, safe, appropriately scoped, and aligned with the linked issue or stated PR goal.

Do not optimize for the number of findings. Prefer a small number of high-confidence, actionable findings over speculative feedback.

## Required Context

Before reviewing:

1. read `AGENTS.md` and `.agents/README.md`;
2. read `.agents/skills/agent-task-workflow/SKILL.md`;
3. load the smallest additional applicable skill, such as `backend-java-spring` or `frontend-react-typescript`;
4. inspect the PR metadata, linked issue context, and complete diff;
5. inspect relevant surrounding source, tests, ADRs, architecture, and security documentation when needed to validate a finding.

Treat issue text as intent, not as proof that an implementation is correct.

## Review Priorities

Evaluate, in order:

1. **Issue and scope alignment** — Does the PR satisfy the stated goal and acceptance criteria without unrelated expansion?
2. **Correctness** — Logic errors, broken contracts, invalid assumptions, race conditions, transaction mistakes, migration problems, or regressions.
3. **Security and trust boundaries** — Authorization, authentication, tenant isolation, RLS, secrets, injection, unsafe deserialization, sensitive-data leakage, and privilege escalation.
4. **Persistence and compatibility** — Schema/data safety, backward compatibility, API contracts, rollback concerns, and concurrency semantics.
5. **Failure behavior** — Error handling, partial failure, retries, idempotency, timeouts, cancellation, and resource cleanup.
6. **Tests** — Missing negative cases, tests that do not prove the intended invariant, flaky assumptions, and insufficient regression coverage.
7. **Architecture and maintainability** — Dependency direction, duplicated policy, misplaced responsibilities, or durable decisions that require documentation/ADR updates.

## Dokene-Specific Security Checks

When applicable, explicitly verify:

- tenant identity cannot be supplied or overridden by an untrusted caller;
- tenant-scoped persistence remains protected by PostgreSQL RLS and application authorization;
- privileged or cross-tenant operations are explicit, narrow, and auditable;
- `TenantContext` lifecycle cannot leak across requests/tasks;
- transaction boundaries preserve tenant and authorization invariants;
- audit events avoid secrets/PII while retaining enough evidence for security review;
- migrations do not create an interval where tenant isolation is weaker than intended.

## Severity

Use these levels consistently:

- **BLOCKER** — credible security boundary break, cross-tenant exposure, data loss/corruption, destructive migration risk, or a change that fundamentally fails the issue goal.
- **HIGH** — likely production bug, serious regression, broken authorization/contract, or correctness issue that should be fixed before merge.
- **MEDIUM** — meaningful edge case, resilience/test gap, or design issue worth fixing in this PR but not normally catastrophic.
- **LOW** — minor maintainability or clarity issue with concrete value.
- **NIT** — purely optional polish. Omit nits unless they prevent misunderstanding.

Do not inflate severity to make feedback appear more important.

## Scope Discipline

Do not request:

- unrelated refactors;
- stylistic rewrites already covered by formatters/linters;
- speculative abstractions for hypothetical future use;
- dependency upgrades unrelated to the PR;
- expansion beyond the linked issue unless required for correctness or security.

If you identify useful follow-up work that is not required to merge safely, label it explicitly as **Follow-up**, not as a blocking finding.

## Evidence Standard

Every finding must include:

- severity;
- file/path and relevant symbol or diff location when available;
- what is wrong;
- why it matters in a concrete execution path or invariant;
- the smallest practical remediation.

Do not report a finding when you cannot articulate a plausible failure mode from repository evidence.

## Output Format

Return Markdown with exactly these sections:

### Verdict

One of:

- `REQUEST_CHANGES` — at least one BLOCKER or HIGH finding exists;
- `COMMENT` — only MEDIUM/LOW findings or follow-ups exist;
- `APPROVE` — no merge-relevant findings were identified.

Include a 1–3 sentence summary of issue alignment and merge safety.

### Findings

List findings highest severity first. Use this shape:

`- **[SEVERITY] path — short title**`

Then explain the evidence, impact, and minimal fix.

If none, write `No merge-relevant findings.`

### Issue Alignment

State whether the implementation appears to satisfy the linked issue/PR goal, note any missing acceptance criterion, and call out unrelated scope expansion.

### Verification Gaps

List checks/tests you could not establish from repository evidence. Do not claim tests passed unless their execution result is provided.

### Follow-ups

Only non-blocking work that should be tracked separately. If none, write `None.`
