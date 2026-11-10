# Example: membership-change authority

A worked record for an authorization change, produced with [`blast-radius-analysis`](../SKILL.md). Use it as the pattern for a follow-up policy or authorization change, such as a new membership operation, a new permission, or a moved authorization decision.

**Change analysed.** [Issue #325](https://github.com/stevdrey/dokene/issues/325), merged as `38deb90` on base `01c8490`: invite, reactivate, change role, and revoke must decide against the actor's membership as it is in the writing transaction, not as the request resolved it ([security invariant 30](../../../../docs/security/security-invariants.md), [ADR 0031](../../../../docs/adr/0031-membership-invitation-acceptance-and-audited-reactivation.md) §6).

**Limitation.** This record was written after the change merged, with its PR available. It shows the format and the probing method; it is not evidence of what the analysis would have found beforehand. The pilot comparison is [Issue #376](https://github.com/stevdrey/dokene/issues/376)'s job. Citations are pinned to `38deb90`; paths are relative to `backend/src/main/java/io/github/stevdrey/dokene/` unless they start with `backend/`, `frontend/`, or `docs/`.

## How the trace went

1. **Trigger.** The change crosses authorization, persistence (row locks in the write path), and tenant scope, and it adds invariant 30. The skill applies.
2. **Entry points.** `tenant/api/MembershipController.java:47`, `:63`, `:77`, and `:83` (invite, role, reactivation, revoke). They call `MembershipService` and `MembershipRoleService`, which now run each mutation through `MembershipChangeAuthorizer.change` (`tenant/application/MembershipService.java:62`, `:101`, `:120`; `tenant/application/MembershipRoleService.java:34`).
3. **The safety-critical facts.** The change is safe only if (a) the authority is re-decided under the locks before the mutation runs, and (b) no other code writes a membership around that path. Both could plausibly be false, so both get a probe. Lock ordering and serialization are a third fact, and the budget keeps it at `cited` (see `uncertainty`).
4. **Boundaries that turned out unaffected** were cleared with the search or file that shows it, instead of being analysed further.

## Record

```yaml
change: "01c8490..38deb90 (Issue #325): membership changes re-decide the actor's authority under row locks in the writing transaction"
trigger: "Crosses auth, persistence and tenant scope; adds security invariant 30"
affected_boundaries: [auth, persistence, rls_tenant_scope, api]
safety_assumptions:
  - fact: "Each change re-decides the actor's current membership under the locks and runs no mutation when the decision is a denial"
    invariant: "Security invariant 30; ADR 0031 §6"
    level: reproduced
    evidence: "tenant/application/MembershipChangeAuthorizer.java:62-66; probe P2"
  - fact: "Only the known use cases write a membership through the repository port, so no write bypasses MembershipChangeAuthorizer"
    invariant: "ADR 0031 §6"
    level: reproduced
    evidence: "backend/src/test/java/io/github/stevdrey/dokene/architecture/ModuleDependencyArchitectureTest.java:59-64; probe P1"
probes:
  - id: P1
    command: "./gradlew test --tests '*ModuleDependencyArchitectureTest'   # in a detached worktree at 38deb90, outside the candidate"
    kind: automated
    result: passed
    negative_control: "Added a class in tenant.application that calls TenantMembershipRepository.save: membershipsAreWrittenOnlyByKnownUseCases failed"
  - id: P2
    command: "./gradlew test --tests '*tenant.application.MembershipServiceTest'   # same worktree"
    kind: automated
    result: passed
    negative_control: "Removed the denial check at MembershipChangeAuthorizer.java:63-65: 4 of 20 tests failed, including anActorDemotedAfterTheRequestResolvedIsRefusedForEveryChangeAndAudited"
uncertainty:
  - "unverified here: PostgreSQL serializes concurrent changes (ORDER BY id before FOR NO KEY UPDATE, tenant row FOR SHARE). Cited at tenant/persistence/jpa/SpringDataTenantMembershipRepository.java:18-31; the Testcontainers suite MembershipChangeAuthorityIntegrationTest covers it but was not re-run within this record's budget"
  - "unverified: the architecture rule covers only TenantMembershipRepository.save(TenantMembership). A future port method or native UPDATE that writes memberships would not be caught by it. Reasoned from tenant/domain/TenantMembershipRepository.java:9-22, where save is today the only writing method"
cleared:
  - "Frontend: no caller of /api/memberships in frontend/src (git grep at 38deb90 is empty). The generic 409 message in frontend/src/api/apiClient.ts:5 is unaffected"
  - "API contract: a refusal stays 403 (TenantAccessDeniedException, MembershipChangeAuthorizer.java:70). Concurrent changes now serialize instead of returning an optimistic-lock 409; no frontend consumer relies on that 409"
  - "Migrations: none. The runtime role's grants (V16, V36) and the RLS update policies already allow the locking clauses"
  - "Messaging: the tenant module may not depend on messaging (ModuleDependencyArchitectureTest.java:66-70); invariants 3, 4, 8, 24 and 26 are not reached"
  - "Invitation acceptance writes through TenantInvitationDirectory.accept, not the port's save. It is the invitee's own action under invariant 28, not an actor-authority change, so invariant 30 does not apply to it"
proposed_tests:
  - "A demotion of the actor committed while the change waits on the lock refuses the change and audits the denial (needs two database connections)"
  - "A refusal under the lock rolls back before it is audited, so the audit does not need a second pooled connection"
  - "An architecture rule that also covers any new membership-writing port method, if one is added"
```

## Reading the record

- Both safety-critical facts reached `reproduced`: each probe passed on the change and failed when the protection was removed in a disposable copy.
- The concurrency fact stayed at `cited` because the budget is two probes. It is listed as `unverified here` with the test that covers it, not presented as proven.
- The rule-coverage gap is an `uncertainty`, not a blocker: no executed probe and no cited violation show a bypass today.
- For a follow-up change of this kind, re-run P1 and P2, add the new operation to the list of use cases that must route through `MembershipChangeAuthorizer.change`, and decide whether the new path needs the concurrency suite.

The exercise that produced this record, with its commands and results, is in [the Issue #373 verification record](../../../../docs/verification/issue-373-blast-radius-exercise.md).
