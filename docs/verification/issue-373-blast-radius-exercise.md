# Issue #373 blast-radius analysis: exercise record

Status: part of [Epic #371](https://github.com/stevdrey/dokene/issues/371), step 2 of 5. It exercises the new [`blast-radius-analysis`](../../.agents/skills/blast-radius-analysis/SKILL.md) skill on a historical authorization change and compares the result with the tests that change shipped. It changes no application behavior.

This record is sanitized: outcomes and test names only. All data in the tests is synthetic. No live service, provider, or message send was involved.

## Setup

- **Subject:** [Issue #325](https://github.com/stevdrey/dokene/issues/325), merged as `38deb90` (PR #364) on base `01c8490`. It moves membership-change authorization into the writing transaction under row locks (security invariant 30, ADR 0031 §6). It was chosen because it is a recent authorization change that crosses auth, persistence, and tenant scope, and it ships its own tests to compare against.
- **Isolation:** the probes ran in a detached `git worktree` at `38deb90` in a scratch directory outside the candidate worktree, per rule 8 of the [pstack audit](pstack-applicability-audit.md#rules-for-child-issues). The negative-control edits were made only in that copy and reverted afterwards; the copy was then removed.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build. Neither probe needs Docker.
- **Output:** the record in [the skill's example](../../.agents/skills/blast-radius-analysis/examples/membership-change-authority.md).

## Results

| Probe | Command (in the `38deb90` copy, `backend/`) | Kind | Result |
| --- | --- | --- | --- |
| P1, P2 baseline | `./gradlew test --tests '*ModuleDependencyArchitectureTest' --tests '*tenant.application.MembershipServiceTest'` | automated | BUILD SUCCESSFUL: `ModuleDependencyArchitectureTest` 22 tests, `MembershipServiceTest` 20 tests, 0 failures |
| P1, P2 negative controls | Same command after removing the denial check at `MembershipChangeAuthorizer.java:63-65` and adding a class in `tenant.application` that calls `TenantMembershipRepository.save` | automated | BUILD FAILED as intended: 42 tests, 5 failed. `membershipsAreWrittenOnlyByKnownUseCases` (P1) and 4 `MembershipServiceTest` cases (P2): `aRefusalUnderTheLockSkipsTheMutationAndIsAuditedOnlyAfterTheTransactionEnded`, `aRevokedActorIsRefusedEvenWhileItAdministersAnotherTenant`, `aReplacedActorMembershipDoesNotCarryTheRequestsAuthority`, `anActorDemotedAfterTheRequestResolvedIsRefusedForEveryChangeAndAudited` |

Both safety-critical assumptions, as narrowed in the record, therefore reached the `reproduced` level. P1 proves only that a writer outside the allow-list is caught, not that the allow-listed classes write inside `MembershipChangeAuthorizer.change`; the record keeps that `unverified`.

## Comparison with the project's tests

| Item from the record | Project test shipped by #325 | Match |
| --- | --- | --- |
| Proposed: a demotion committed while the change waits refuses it and audits the denial | `MembershipChangeAuthorityIntegrationTest.aDemotionCommittedWhileTheChangeWaitsRefusesItAndAuditsTheDenial` | Yes |
| Proposed: a refusal rolls back before it is audited | `MembershipChangeAuditPoolIntegrationTest.aRefusalUnderTheLockIsDeniedAndAuditedWithASingleConnection`, `MembershipServiceTest.aRefusalUnderTheLockSkipsTheMutationAndIsAuditedOnlyAfterTheTransactionEnded` | Yes |
| Proposed: extend the architecture rule if a new membership-writing port method is added | None; today `save` is the only writing method of the port | Conditional, no current gap |
| Uncertainty: serialization of concurrent changes, not re-run within the budget | Reverse-order, mutual-revocation, rollback, and tenant-suspension cases in `MembershipChangeAuthorityIntegrationTest` | Covered by the project; the record correctly left it `unverified here` |
| Cleared: no frontend consumer; the 409-to-serialization change affects no caller | `AuditIntegrationTest.concurrentRoleChangesAreSerializedAndEachAuditsTheRoleItReplaced` covers the backend side | Consistent |

Observations:

- The record proposed nothing that contradicts the shipped tests and raised no blocker. Its one gap (rule coverage of future write methods) is labelled `unverified`, not proven.
- The shipped suite is broader than the record's two probes: cross-tenant non-blocking, replaced memberships, and lock-order cases came from the original PR, not from this analysis. The skill's budget is deliberately smaller than the change's own test plan, which `.agents/README.md` still requires.
- The analysis was written with the merged PR available, so this exercise shows that the format and the probe method work. It does not measure how many defects the skill finds before a change merges; [Issue #376](https://github.com/stevdrey/dokene/issues/376) owns that measurement.

## Not run

- `MembershipChangeAuthorityIntegrationTest` and the other Testcontainers suites were not re-run for this record; the #325 [verification record](issue-325-membership-change-authority-verification.md) holds their results.
- No frontend, browser, or manual QA was run. The skill and this record change no application code.
