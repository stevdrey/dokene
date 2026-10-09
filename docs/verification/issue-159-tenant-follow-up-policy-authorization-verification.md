# Issue 159 verification: tenant-wide follow-up policy requires TENANT_UPDATE

Scope: backend authorization and documentation. No API shape, migration or frontend change (the frontend only calls `GET /api/follow-up-policy`).

Problem: `PUT /api/follow-up-policy` (tenant cadence and time zone) required only `FOLLOWUP_WRITE`, which OPERATOR holds, so one operator could reshape the due classification of every customer in the workspace.

Change: `FollowUpService.configureTenant` now requires `TENANT_UPDATE` (OWNER/ADMIN). Per-customer policy, snooze, dismissal and manual completion keep `FOLLOWUP_WRITE`; reads keep `FOLLOWUP_READ`. Denials keep the existing contract (403, audited with the required permission).

## Automated

- `./gradlew check` (JDK 26.0.2): 1083 tests, 0 failed, 0 skipped. The four affected classes (87 tests) are listed in `issue-159-evidence/test-run.txt`.
- `FollowUpServiceTest`: `configureTenant` requires `TENANT_UPDATE` and never `FOLLOWUP_WRITE`; a denial does not reach the repository.
- `FollowUpControllerTest`: tenant `PUT` returns 403 when the service denies.
- `FollowUpManagementIntegrationTest` (real database): ADMIN updates the tenant policy with version checking; OPERATOR keeps tenant policy read, per-customer policy, snooze, manual completion and dismissal.
- `FollowUpCurlSystemTest` (real HTTP via curl): OPERATOR and VIEWER get 403 on the tenant `PUT`, OPERATOR can still `GET`, ADMIN and OWNER succeed with `If-Match`.

Not covered by an automated test: the audited denial with `TENANT_UPDATE` for OPERATOR. The integration class runs with a one-connection pool and the independent audit transaction needs a second one, so such a test hangs; the audit row is verified against the live stack below.

## Manual, local environment (backend rebuilt from this branch)

Synthetic users `testuser` (OWNER), `testoperator` (OPERATOR) and `testviewer` (VIEWER) through the BFF. Results in `issue-159-evidence/live-api-check.txt` (11 checks, all pass):

- `GET /api/follow-up-policy`: 200 for all three roles.
- `PUT /api/follow-up-policy`: OPERATOR 403, VIEWER 403, OWNER 200, OWNER with a stale `If-Match` 409.
- OPERATOR: customer policy `GET`/`PUT` 200 and follow-up queue 200; VIEWER customer policy `PUT` 403.
- Audit (`issue-159-evidence/audit-rows.txt`): `AUTHORIZATION_DENIED` with `permission = TENANT_UPDATE` for the denied calls and `TENANT_FOLLOW_UP_POLICY_CHANGED` for the OWNER change.

No ADMIN user exists in the QA seed; ADMIN success is covered by the integration and system tests above.
