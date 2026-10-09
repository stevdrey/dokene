# Issue 159 verification: tenant-wide follow-up policy requires TENANT_UPDATE

Scope: backend authorization and documentation. No API shape, migration or frontend change (the frontend only calls `GET /api/follow-up-policy`).

Problem: `PUT /api/follow-up-policy` (tenant cadence and time zone) required only `FOLLOWUP_WRITE`, which OPERATOR holds, so one operator could reshape the due classification of every customer in the workspace.

Change: `FollowUpService.configureTenant` now requires `TENANT_UPDATE` (OWNER/ADMIN). Per-customer policy, snooze, dismissal and manual completion keep `FOLLOWUP_WRITE`; reads keep `FOLLOWUP_READ`. Denials keep the existing contract (403, audited with the required permission).

## Automated

- `./gradlew check` (JDK 26.0.2): 1085 tests, 0 failed, 0 skipped. The five affected classes (89 tests) are listed in `issue-159-evidence/test-run.txt`.
- `FollowUpServiceTest`: `configureTenant` requires `TENANT_UPDATE` and never `FOLLOWUP_WRITE`; a denial does not reach the repository.
- `FollowUpControllerTest`: a real `FollowUpService` behind the controller returns 403 on the tenant `PUT` when `TENANT_UPDATE` is denied, without touching the policy repository.
- `FollowUpTenantPolicyAuthorizationIntegrationTest` (real database, own 4-connection pool): OPERATOR and VIEWER are denied, the denial is audited with `TENANT_UPDATE` and the policy is unchanged.
- `FollowUpManagementIntegrationTest` (real database): ADMIN updates the tenant policy with version checking; OPERATOR keeps tenant policy read (14 days, UTC), per-customer policy, snooze, manual completion and dismissal.
- `FollowUpCurlSystemTest` (real HTTP via curl): OPERATOR and VIEWER get 403 on the tenant `PUT`, OPERATOR can still `GET`, ADMIN and OWNER succeed with `If-Match`.

## Manual, local environment (backend rebuilt from this branch)

Synthetic users `testuser` (OWNER), `testoperator` (OPERATOR) and `testviewer` (VIEWER) through the BFF. Results in `issue-159-evidence/live-api-check.txt` (11 checks, all pass):

- `GET /api/follow-up-policy`: 200 for all three roles.
- `PUT /api/follow-up-policy`: OPERATOR 403, VIEWER 403, OWNER 200, OWNER with a stale `If-Match` 409.
- OPERATOR: customer policy `GET`/`PUT` 200 and follow-up queue 200; VIEWER customer policy `PUT` 403.
- Audit (`issue-159-evidence/audit-rows.txt`, rows written during that single run): 2 `AUTHORIZATION_DENIED` with `permission = TENANT_UPDATE` (OPERATOR, VIEWER) and 1 `TENANT_FOLLOW_UP_POLICY_CHANGED` (OWNER).

No ADMIN user exists in the QA seed; ADMIN success is covered by the integration and system tests above.
