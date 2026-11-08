# Issue #325 membership-change authority revalidation: verification record

Status: single PR, part of epic [#305](https://github.com/stevdrey/dokene/issues/305). The design is in [ADR 0031](../adr/0031-membership-invitation-acceptance-and-audited-reactivation.md) §6 and [security invariant 30](../security/security-invariants.md).

This record is sanitized: it gives outcomes and test names only. It contains no credential, signed capability, or reproduction script beyond the automated tests in the repository. All data is synthetic.

## Environment

- **Run:** 2026-11-08, branch `325-security-revalidate-authority-for-membership-changes`, based on `main` at `01c8490` (through #363). The analysis named a worktree at `a328848`; the code was rechecked on the base first and the finding still applied: `MembershipService` (invite, reactivate, revoke) and `MembershipRoleService` (role change) authorized only through `DefaultTenantAuthorizationService`, which evaluates the role and status cached in the request's `TenantContext`. Nothing re-read the actor's membership in the writing transaction, and nothing serialized the change with a concurrent change to the actor. The concurrent behavior had not been reproduced by the analysis; the negative control in Results shows it with the revalidation disabled. **Tested revision:** first `0aa0678`, then the review follow-up commit that updates this record, on top of it (a commit cannot name its own hash); the PR names it. The Results below are for the follow-up.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build.
- **Database:** Testcontainers PostgreSQL 17, migrated by Flyway (no new migration). Concurrent changes run on a raw `dokene_runtime` connection bound to the tenant with a signed context, as an application instance would.

## Change

- `TenantMembershipRepository.lockForMembershipChange(tenantId, identityIds)`, mandatory transaction: `SELECT id FROM dokene.tenants … FOR SHARE`, then `SELECT id … WHERE tenant_id = ? AND identity_id IN (…) ORDER BY id FOR NO KEY UPDATE`. PostgreSQL sorts before it locks, so every membership change takes its row locks in the same order. The runtime role's column `UPDATE` grants (V16, V36) and the tenant RLS update policies already permit the locking clauses; no grant or policy changed.
- `MembershipChangeAuthorizer.change(permission, target, mutation)` is the single path of a membership change. It checks the request's own context before any transaction; then, in one transaction, takes the locks, re-decides through `CurrentMembershipAuthorizer.denial` (the same membership id, `ACTIVE`, with a role that holds the permission) and runs the mutation. A refusal rolls back first and is audited afterwards.
- `MembershipService.addMembership`, `reactivateMembership`, `revokeMembership` and `MembershipRoleService.changeRole` run their mutation through it and are no longer `@Transactional` themselves. The HTTP contract is unchanged: a refusal is `403`.
- An architecture rule allows only the known use cases to call `TenantMembershipRepository.save`.

## Automated coverage

| Acceptance item | Test |
| --- | --- |
| Concurrent change, the other order: a demotion started after the change took its locks waits (`lock_timeout` reports `55P03`) and succeeds once the change has committed | `MembershipChangeAuthorityIntegrationTest.aDemotionStartedAfterTheChangeLockedWaitsUntilTheChangeCommits` |
| Coordination with the tenant: a tenant suspension waits for the change, while another membership change in the same tenant does not | `MembershipChangeAuthorityIntegrationTest.aTenantSuspensionWaitsForTheChangeButAnotherMembershipChangeDoesNot` |
| Audit under pool pressure: with one pooled connection, a refusal under the lock still answers `403` and persists its denial | `MembershipChangeAuditPoolIntegrationTest.aRefusalUnderTheLockIsDeniedAndAuditedWithASingleConnection`; `MembershipServiceTest.aRefusalUnderTheLockSkipsTheMutationAndIsAuditedOnlyAfterTheTransactionEnded` |
| Single path: only the known use cases write memberships | `ModuleDependencyArchitectureTest.membershipsAreWrittenOnlyByKnownUseCases` |
| Concurrent change: a demotion of the actor committed while the revocation waits on the lock refuses it; the target is unchanged | `MembershipChangeAuthorityIntegrationTest.aDemotionCommittedWhileTheChangeWaitsRefusesItAndAuditsTheDenial` |
| Audit: that refusal persists exactly one `AUTHORIZATION_DENIED` / `DENIED` / `MEMBERSHIP_REVOKE` row for the actor's membership under the request's correlation id, despite the rollback | same test |
| Concurrent change: a revocation of the actor committed while an invitation, a role change or a reactivation waits refuses each one; nothing is written | `MembershipChangeAuthorityIntegrationTest.aRevocationCommittedWhileTheChangeWaitsRefusesEveryKindOfChange` |
| Coordination: the change waits for the concurrent transaction and proceeds when that transaction rolls back | `MembershipChangeAuthorityIntegrationTest.aDemotionThatRollsBackLetsTheWaitingChangeProceed` |
| Coordination: two administrators revoking each other at the same time end with exactly one revocation and one `403`, never a deadlock or two revocations | `MembershipChangeAuthorityIntegrationTest.twoAdminsRevokingEachOtherConcurrentlyNeverBothSucceedNorDeadlock` |
| Tenant isolation: a pending change to the same identity's membership in another tenant does not block the change; an actor revoked here but owner elsewhere is refused | `MembershipChangeAuthorityIntegrationTest.aPendingChangeToTheSameIdentityInAnotherTenantNeitherBlocksNorAuthorizesTheChange` |
| Denied requests: an actor demoted after its request resolved is refused for all four changes, each refusal is audited with the role reason, and no membership audit event is written | `MembershipServiceTest.anActorDemotedAfterTheRequestResolvedIsRefusedForEveryChangeAndAudited` |
| Denied requests and tenant isolation: a revoked actor who is still administrator of another tenant is refused with the inactive-membership reason, attributed to the request's tenant | `MembershipServiceTest.aRevokedActorIsRefusedEvenWhileItAdministersAnotherTenant` |
| A removed and re-admitted actor (new membership id) does not carry the request's authority | `MembershipServiceTest.aReplacedActorMembershipDoesNotCarryTheRequestsAuthority` |
| Every change locks exactly the actor and the target in the request's tenant; a request whose own context lacks the permission is refused before any lock | `MembershipServiceTest.everyMembershipChangeLocksTheActorAndTheTargetBeforeDeciding`, `MembershipServiceTest.anActorPromotedAfterTheRequestResolvedIsStillFirstCheckedAgainstTheRequest` |
| Two concurrent role changes of the same target by the same actor are serialized: the second waits on the row lock without reading the target, then applies on top of the first, and the audit records `VIEWER>ADMIN` then `ADMIN>OPERATOR` | `AuditIntegrationTest.concurrentRoleChangesAreSerializedAndEachAuditsTheRoleItReplaced` (replaces `concurrentRoleChangesProduceOnlyOneSuccessEvent`, see Notes) |
| Existing membership flows, grants and HTTP behavior still hold | `MembershipManagementIntegrationTest`, `TenantMembershipPrivilegeIntegrationTest`, `MembershipControllerTest`, `MembershipServiceTest` (existing cases) |

## Results

| Command | Kind | Result |
| --- | --- | --- |
| `./gradlew test --tests '*MembershipServiceTest' --tests '*CurrentMembershipAuthorizerTest' --tests '*ModuleDependencyArchitectureTest' --tests '*MembershipChangeAudit*' --tests '*MembershipChangeAuthority*' --tests '*AuditIntegrationTest' --tests '*MembershipManagementIntegrationTest' --tests '*TenantMembershipPrivilegeIntegrationTest'` | automated | 102 tests, 0 failures |
| Negative controls (local, not committed): denial audited inside the transaction; tenant lock removed; membership lock removed | automated | 2 tests failed (the single-connection test and the audit-order unit test); 1 failed (the suspension test); 5 of 7 failed (including the reverse-order and mutual-revocation tests) |
| `./gradlew check` (backend) | automated | BUILD SUCCESSFUL: 2556 tests, 0 failures, 0 errors, 0 skipped. The first full run, before the review follow-up, failed only `AuditIntegrationTest.concurrentRoleChangesProduceOnlyOneSuccessEvent`, which forced the race the lock now prevents; it was rewritten (see Notes) |

Notes:

- Behavior change: two concurrent changes to the same membership used to race, and the loser got an optimistic-lock `409` with one audit event. They now serialize on the row lock, so the later one is applied to the state the earlier one committed, and both are audited with the role each replaced. The optimistic `version` check stays as a backstop. The existing audit test asserted the race (both reads before either write), which the lock now prevents by design, so it was rewritten to assert the serialization instead.

- The main integration test sets the Hikari pool to 2 connections, one for each change running at once. The denial audit no longer needs a connection of its own while a change holds one; `MembershipChangeAuditPoolIntegrationTest` proves it with the shared fixture's single connection.

Not run: a live multi-instance deployment. The serialization is enforced by PostgreSQL row locks, which the integration test exercises across separate connections. No frontend change was made, so no frontend checks were run.
