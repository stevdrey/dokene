# Issue #315 runtime membership privileges: verification record

Status: single PR. It closes audit finding L5 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md): the runtime role had full `SELECT, INSERT, UPDATE, DELETE` on `dokene.tenant_memberships`. The design is in [ADR 0003](../adr/0003-postgresql-rls.md) (Tenant root and provisioning records).

This record is sanitized: it gives grants, outcomes and test names only. It contains no reproduction script, capability value or secret.

## Environment

- **Run:** 2026-11-04, branch `315-securityl5-narrow-runtime-membership-database-privileges`, based on `main` at `6cdc000` (the audit's tree `c4b62d1` plus the fixes for #306, #307, #308 and #313). The code was rechecked on that base before the change: L5 still applied as reported, since no migration after V1 touched the membership grants. **Tested revision:** `39d0973`, the first commit of this branch. Every result in the Results table below was run on that tree. Later commits change only this record.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build.
- **Database:** Testcontainers PostgreSQL 17, migrated by Flyway through V36.
- **Data:** synthetic tenants and members only.

## Change

Migration V36:

| Privilege of `dokene_runtime` on `tenant_memberships` | Before | After |
| --- | --- | --- |
| `SELECT`, `INSERT` | granted | granted (unchanged) |
| `UPDATE` | every column | only `role`, `status`, `updated_at`, `version` |
| `DELETE` | granted, with a tenant RLS delete policy | revoked; the delete policy is dropped |

- The application already matched these grants: no code deletes a membership (revocation and suspension are status changes), and `TenantMembershipEntity` marks `id`, `tenant_id`, `identity_id` and `created_at` as not updatable, so Hibernate never writes them after insert. No Java code changed.
- The migration is expand-safe: a backend of the previous release issues no statement V36 refuses.
- `tenant_memberships_migration_policy` still lets `dokene_migration` manage rows under forced RLS.

Residual risk: `role` stays updatable because role changes are a supported workflow. An SQL injection inside a member's request could still change a role in the caller's own tenant; RLS keeps it inside that tenant and application authorization guards the workflow. Rewriting `identity_id`, moving a membership to another tenant, backdating it or deleting it is now refused by the database.

## Automated coverage

| Acceptance item | Test |
| --- | --- |
| The runtime role has `SELECT` and `INSERT`, no table-wide `UPDATE`, no `DELETE` or `TRUNCATE`, column `UPDATE` exactly on the four lifecycle columns, and no `DELETE` policy remains | `TenantMembershipPrivilegeIntegrationTest.runtimeRoleHoldsNoDeleteAndUpdatesOnlyTheLifecycleColumns` |
| With its own tenant's signed context, a runtime session cannot delete a membership (by id or unscoped), truncate the table, or update `identity_id`, `tenant_id`, `id` or `created_at` (all `42501` permission denied); the row is unchanged; updating `role`, `status`, `updated_at` and `version` still succeeds | `TenantMembershipPrivilegeIntegrationTest.runtimeRoleCannotDeleteOrRewriteImmutableColumnsOfItsOwnTenantsMemberships` |
| Invitation (`addMembership`), role change and revocation run through `MembershipService` and persist; the revoked row still exists with status `REVOKED` and its original tenant, identity and creation time | `TenantMembershipPrivilegeIntegrationTest.invitationRoleChangeAndRevocationStillPersistThroughTheApplication` |
| Unscoped runtime `DELETE` and the cross-tenant reassignment of `tenant_id` are refused with permission denied; Flyway reaches V36 with four membership policies | `TenantPersistenceIntegrationTest` (`enforcesTenantIsolationAtTheDatabaseLayerWithRowLevelSecurity`, `blocksCrossTenantInsertsAndReassignmentsAtTheDatabaseLayer`, `migratesTheTenantFoundationWithLeastPrivilegeRuntimeAccess`) |
| A cross-tenant `DELETE` on a raw runtime connection is refused | `TenantIsolationSecurityIntegrationTest.enforcesRlsAgainstDirectRuntimeSqlWithoutApplicationPredicates` |
| Role changes keep their audit attribution, conflicts and ownership guards | `AuditIntegrationTest` |
| Repository-level role changes, revocations and stale-update rejection | `TenantPersistenceIntegrationTest` |

## Results

Runs on `39d0973`:

| Command | Result |
| --- | --- |
| `./gradlew test --tests '*TenantMembershipPrivilegeIntegrationTest' --tests '*TenantPersistenceIntegrationTest' --tests '*TenantIsolationSecurityIntegrationTest' --tests '*AuditIntegrationTest' --tests '*TenantRootRowLevelSecurityIntegrationTest'` | passed: 3, 37, 7, 25 and 4 tests, no failures or skips |
| `./gradlew check` (backend) | passed: 2372 tests in 197 classes, no failures, errors or skips |

The frontend is unchanged and was not rerun.
