# Issue #317 membership invitation acceptance and reactivation: verification record

Status: single PR. It closes audit finding L2 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md). Before the fix, adding a member created an `ACTIVE` membership without the invitee's consent, and a revoked member could never be added again. The design is in [ADR 0031](../adr/0031-membership-invitation-acceptance-and-audited-reactivation.md).

This record is sanitized. It gives endpoints, outcomes and test names only, and contains no reproduction script, capability value, identity or secret.

## Environment

- **Run:** 2026-11-04, branch `317-securityl2-require-membership-invitation-acceptance-and-support-audited-reactivation`, based on `main` at `156f275`. The code was rechecked on that base before the change. L2 still applied as reported: `MembershipService.addMembership` called `TenantMembership.createActive`, and a second add of a revoked identity returned `409`.
- **Tested revision:** `755c3ce`, which applies the code review fixes on top of the first commit `2f69a87` and the QA seeder fix `aab9890`. Every result in the Results table below was run on that content. The two frontend commits after it, `715a80f` and `7ae43ae`, are covered in "Background workspace refresh" below. Later commits change only this record.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build; Node with the repository's Vitest and TypeScript.
- **Database:** Testcontainers PostgreSQL, migrated by Flyway through V37.
- **Data:** synthetic tenants and identities only.

## Change

| Area | Before | After |
| --- | --- | --- |
| `POST /api/memberships` | `201`, `ACTIVE` | `201`, `INVITED`; no tenant context until accepted |
| Invitee | could enter immediately | lists `GET /api/tenants/invitations`, accepts with `POST /api/tenants/{tenantId}/invitation/acceptance` (`200`, or `404`) |
| Revoked member | no way back (`409`) | `POST /api/memberships/{identityId}/reactivation` (`MEMBERSHIP_INVITE`): `REVOKED` becomes `INVITED`, same id and role, and must be accepted again |
| Audit | `MEMBERSHIP_CREATED`, `MEMBERSHIP_REVOKED` | adds `MEMBERSHIP_INVITATION_ACCEPTED` (actor: invitee) and `MEMBERSHIP_REACTIVATED` (actor: administrator), both checked by `ck_audit_shape` |
| UI | none | pending invitations, with the workspace name, role, date and tenant id, on the "no workspaces" page and the settings tab |

The role and ownership rules are unchanged: `OWNER` is never invited, accepted or reactivated, and owners can be neither demoted nor revoked.

## Automated coverage

| Acceptance item | Test |
| --- | --- |
| An invitee is absent from `GET /api/tenants`, gets `403` from `GET /api/tenants/{id}` and from `GET /api/memberships`, sees only its own invitation, and can enter only after accepting. A second acceptance is `404`. The acceptance is audited with the invitee as actor | `MembershipManagementIntegrationTest.invitedIdentityCannotEnterTheWorkspaceUntilItAccepts` |
| Another identity, a member of the tenant, an unknown tenant and a request without CSRF cannot accept (`404`, `404`, `404`, `403`). A revoked invitation cannot be accepted and is no longer listed | `MembershipManagementIntegrationTest.onlyTheInviteeCanAcceptAndARevokedInvitationCannotBeAccepted` |
| Revocation removes access, re-adding stays `409`, and reactivation returns the same membership as `INVITED` without access. A second reactivation is `409`. The member regains access only after accepting. Both events are stored and read back through `AuditReader` | `MembershipManagementIntegrationTest.revokedMemberIsReactivatedAsAnInvitationAndMustAcceptAgain` |
| An operator or a non-member cannot reactivate (`403`). An active membership or the owner cannot be reactivated (`409`), and an absent one is `404`. The owner stays `ACTIVE` `OWNER` | `MembershipManagementIntegrationTest.reactivationIsAuthorizedAndLimitedToRevokedNonOwnerMemberships` |
| A viewer is refused reactivation (`403`, audited) before the path is validated | `AuthorizationBeforeValidationIntegrationTest` |
| The acceptance is the only mutating endpoint without a tenant permission, with a written justification | `RequiredPermissionCoverageTest` |
| Invitation, role change, revocation and reactivation persist under the V36 column grant | `TenantMembershipPrivilegeIntegrationTest.invitationRoleChangeAndRevocationStillPersistThroughTheApplication` |
| Domain: only `REVOKED` reactivates, the id and role are kept, an owner never reactivates, timestamps cannot go backwards | `TenantMembershipTest` |
| Service: invitations are `INVITED`, reactivation is authorized and audited, refusals write no audit | `MembershipServiceTest`, `MembershipInvitationServiceTest` |
| Audit shape and delegation | `AuditEventTest`, `DurableMembershipAuditAdapterTest`, `TenantControllerTest` |
| Flyway reaches V37 | `TenantPersistenceIntegrationTest` |
| UI: lists, accepts without a tenant header and with CSRF, handles `404`, failure and load errors | `PendingInvitations.test.tsx`, `NoMembershipsView.test.tsx`, `App.test.tsx` |

## Results

Runs on `755c3ce`:

| Command | Kind | Result |
| --- | --- | --- |
| `./gradlew check` (backend) | automated | passed: 2395 tests in 198 classes, no failures, errors or skips |
| `npm test` (frontend) | automated | passed: 1110 tests in 47 files. A first run alongside the backend `check` timed out in four unrelated suites (5 s limit, CPU contention); run on its own, it passed |
| `npm run build` (frontend, `tsc -b` and Vite) | automated | passed |
| `./scripts/seed-local-qa.sh --verify`, then revoke `testviewer` and run it again | live (local Compose stack) | passed: the second run reactivated and accepted the viewer, and all six RBAC checks passed both times |

Earlier runs: `./gradlew check` passed 2393 tests and `npm test` 1109 tests on `2f69a87`, and the seeder runs in "QA seeder" below ran on `aab9890`.

The code review of PR #355 led to these changes:
- The acceptance takes the application clock as a fourth argument, because a database-stamped `updated_at` could be ahead of the clock that later transitions use.
- `invitedAt` is the creation time.
- The acceptance's audit context comes from `TenantContextResolver`.
- A failed acceptance is no longer audited, because any identity could amplify it.
- The membership status audit default fails closed.
- Accepting refreshes the workspace list in the background.
- The QA seeder stops when it cannot list memberships.

V37 was edited before release. The local database that already had it was repaired by dropping its two functions and its history row, then re-applied by the migrate job, with no data loss.

## Background workspace refresh

Two optional findings from the last verification round, and a later review comment on PR #355, were first fixed in the stacked PR #356. Its two commits were then moved into this PR with a fast-forward. They change only `TenantContext.tsx`, `PendingInvitations.tsx` and `PendingInvitations.test.tsx`.

- **Race (`715a80f`):** a background refresh no longer bumps the refresh generation. A background refresh that fails can therefore no longer discard a foreground refresh that is still loading, and leave the app on "loading".
- **Failure feedback (`715a80f`):** a failed background refresh now rejects to its caller.
- **Retry (`7ae43ae`):** if the refresh fails after a successful acceptance, the panel says the invitation was accepted but the workspace list could not be refreshed. It offers "Reintentar actualización", which reloads only the list and never posts the acceptance again. A successful retry clears the message.

| Command | Kind | Result |
| --- | --- | --- |
| `npx vitest run --maxWorkers=2` (frontend) on `7ae43ae` | automated | passed: 1112 tests in 47 files. An unrestricted run was once killed for lack of memory while the local Docker stack was running. |
| `npm run build` (frontend) on `7ae43ae` | automated | passed |
| Mutations of the fix | automated | each mutation made its test fail while the acceptance-error tests kept passing (see below) |

The mutations:
- Restoring the old generation bump for background refreshes makes the race test fail.
- Five changes to `PendingInvitations.tsx` each make the retry test fail: re-posting the acceptance, removing the button, not clearing the message, using a foreground retry, and hiding the alert when the list is empty.

Two independent verifiers passed `715a80f` and `7ae43ae`. The backend is unchanged by these commits, so the backend results above still apply.

## QA seeder

The code review of PR #355 found that `scripts/seed-local-qa.sh` invited `testoperator` and `testviewer` but never accepted. On a fresh database, `--verify` would therefore fail at its first operator check. Each membership now goes through one step: it is invited (or reactivated if revoked), its role is aligned, and the invitee's own session accepts it with its CSRF token. Every call checks its HTTP status.

| Command | Kind | Result |
| --- | --- | --- |
| `./scripts/dev-env.sh up --yes`, then `./scripts/seed-local-qa.sh --verify` on the existing local data (both memberships already `ACTIVE`) | live (local Compose stack, Keycloak, synthetic users) | passed: all six RBAC checks |
| As the owner, revoke `testviewer` (`204`; the viewer then gets `403`), then run `./scripts/seed-local-qa.sh --verify` again | live | passed: the seeder printed "Reactivating revoked membership of 'testviewer'... INVITED" and "Accepting the invitation as 'testviewer'... ACTIVE", and all six RBAC checks passed |

A fresh invitation uses the same acceptance call. It was not run live, because that needs a database reset, which would destroy the local data.

## Not run

A manual browser walkthrough of the pending-invitations UI with a real OIDC provider. The UI is covered by component tests, and the HTTP contract by MockMvc integration tests against a real PostgreSQL and by the live seeder run above.

## Limitations

- Invitations do not expire, and an invitee cannot decline one. An administrator can revoke a pending invitation.
- A reactivated membership keeps its id, so after re-acceptance it counts as the same membership for ADR 0028's checks (ADR 0031 §5).
