---
name: blast-radius-analysis
description: Use when a change crosses two or more of API contract, persistence or migration, frontend behavior, authentication or authorization, RLS or tenant scope, and messaging side effects, or touches a security invariant. Trace the affected boundaries, prove the one or two facts the change's safety depends on with executable probes, and record evidence, uncertainty, and proposed tests. Optional, risk-triggered pilot practice; it adds no gate.
---

# Blast-Radius Analysis

## When to use

This is an optional pilot practice from [Epic #371](https://github.com/stevdrey/dokene/issues/371), adapted in Dokene's own words from the practices the [pstack applicability audit](../../../docs/verification/pstack-applicability-audit.md) marked **Adapt**. The [Issue #376 decision](../../../docs/verification/issue-376-benchmark-decision.md) keeps it as an optional pilot: no benchmark has yet shown that it finds defects, so it is not a default step.

The default is not to run it. Run it, and say why, only when the change:

- crosses two or more of these boundaries: API contract, persistence or migration, frontend behavior, authentication or authorization, RLS or tenant scope, messaging side effects; or
- touches a numbered item of the [security invariants](../../../docs/security/security-invariants.md).

Skip it, and say so in one line, for documentation-only work or a change inside one module with no contract or invariant impact.

## Boundary

This skill is a step inside `agent-task-workflow`, not a second orchestrator. It does not replace scoping, the tests that `.agents/README.md` requires, `independent-implementation-verification`, the verdict tools, CI, or human review, and it can neither pass nor waive any of them.

It sits at the "applicable skills" level of the `AGENTS.md` precedence: explicit task requirements, accepted ADRs, and security invariants win over anything it concludes. When the analysis finds that the task conflicts with an ADR or invariant, report the conflict; do not resolve it here.

Its record is implementer's evidence. A finding from it becomes a blocker only under the [Blocking bar](#blocking-bar); the analysis never adds a gate of its own.

## Procedure

### 1. Fix the change

Name the base ref, the diff or planned change, and the goal in one sentence. Analyse that change only; adjacent pre-existing problems go to `uncertainty` or a follow-up, not into scope.

### 2. Trace the boundaries

For each boundary, find the code that the change reaches and cite it (`path:line`, pinned to a commit when the tree may move). A boundary with no reach is recorded under `cleared` with the evidence that shows it, such as an empty search.

| Boundary | Where to look in Dokene |
| --- | --- |
| Entry points | Controllers found with `git grep -l '@RestController' backend/src/main/java`, which includes webhook controllers outside `api` packages such as `integration/whatsapp/webhook/`; scheduled tasks and listeners that reach the changed code |
| Callers | Usages of every changed public method or port; ArchUnit rules in `backend/src/test/java/**/architecture/` |
| API contract | Request and response DTOs, status codes, error shapes, and the frontend consumers in `frontend/src/api/` and `frontend/src/features/` |
| Persistence and migrations | Flyway scripts in `backend/src/main/resources/db/migration/`, runtime-role grants, RLS policies, constraints, locking and transaction boundaries |
| Frontend behavior | Components, state, and status-code handling that consume the changed contract |
| Authentication and authorization | Permissions, the authorization services, and where a decision is made relative to the write |
| RLS and tenant scope | Where the tenant comes from, the signed database context, and cross-tenant negative paths |
| Messaging side effects | Outbound sends, outbox and dispatch, webhooks, and invariants that forbid unapproved sends |

Stop tracing a path once it reaches code the change cannot affect. Breadth is for finding the risky fact, not for listing every file.

### 3. Name the safety assumptions

Write down the facts the change's safety depends on: if one is false, an invariant, contract, or tenant boundary breaks. Choose the one or two that are **safety-critical** and could plausibly be false. Everything else is either `cleared` with evidence or listed in `uncertainty`.

### 4. Prove them

Each assumption gets an evidence level. A level is only claimed when its evidence is in the record.

| Level | Meaning |
| --- | --- |
| `asserted` | Stated, with no evidence. Never enough for a safety-critical assumption. |
| `cited` | Code, schema, or documentation that shows the fact, at `path:line`. |
| `reasoned` | An argument over cited code. It can still be wrong about runtime behavior. |
| `executed` | A test or probe that exercises the fact ran and passed. |
| `reproduced` | `executed`, plus a negative control: the probe fails when the protection is removed in a disposable copy. |

For the safety-critical assumptions, aim for `executed`, and for `reproduced` when the negative control is cheap:

- Prefer the narrowest existing test. Otherwise write the test the change needs anyway, or a throwaway probe.
- Write probes, negative-control copies, and their output **outside the candidate worktree**, for example a detached `git worktree` in a scratch directory. The candidate identity includes untracked files.
- Use only invented data and local services. Never send a live message, call a provider, or use real customer data; assert that no send occurred instead.
- When a probe cannot run (the capability is missing, the cost is out of proportion, or it would need a live effect), keep the honest level and add the assumption to `uncertainty` as `unverified`, with the reason. Never raise a level to fill the gap.

### 5. Record

Write one concise record, in the change's PR or the implementer's evidence, in this shape (the audit's format with the change, trigger, and probes made explicit):

```yaml
change: "<base ref, head or planned change, one-sentence goal>"
trigger: "<boundaries crossed or invariant touched>"
affected_boundaries: [api, persistence, frontend, auth, rls_tenant_scope, messaging]
safety_assumptions:
  - fact: "<the fact the change's safety depends on>"
    invariant: "<security invariant number or ADR section>"
    level: asserted | cited | reasoned | executed | reproduced
    evidence: "<path:line, or the probe id>"
probes:
  - id: P1
    command: "<exact command>"
    kind: automated | manual | simulated   # never live: probes do not reach a real provider (step 4)
    result: passed | failed | not-run
    negative_control: "<what was removed and the observed failure, or none>"
uncertainty: ["<unverified: item and reason>"]
cleared: ["<risk checked, and the evidence that it does not apply>"]
proposed_tests: ["<cheapest test that catches the real failure>"]
```

Keep only the boundaries the change reaches in `affected_boundaries`. Redact secrets and personal data from probe output.

### 6. Use the record

- `proposed_tests` feed the implementation. They add to the required tests; they never replace them.
- Pass the record to the independent verifier as implementer's evidence. The verifier re-checks it; it does not trust it.
- Summarize it in the PR: boundaries, the safety assumptions with their levels, and the uncertainty.

## Blocking bar

A finding from this analysis is an actionable blocker only when its evidence is `executed` or `reproduced` (a failing test or probe) or it is a contract or invariant violation shown at `cited` level that needs no runtime assumption. Everything else is reported as a risk or an optional improvement, worded as unverified. A hunch is never presented as proven.

## Budget

One record and one or two probes per change. Run the narrowest probe that can falsify the assumption; the broader suite runs once, in final verification, under the existing rules. Stop when the safety-critical assumptions are `executed` or `reproduced`, or marked `unverified` with a reason.

## Example

[Membership-change authority](examples/membership-change-authority.md) applies this skill to the authorization change of [Issue #325](https://github.com/stevdrey/dokene/issues/325) at commit `38deb90`, and is the pattern for a follow-up policy or authorization change.
