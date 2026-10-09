# Issue #162 — authorization before request binding/validation

## Change
Mutating endpoints guarded by a tenant permission are annotated with `@RequiredPermission`. `RequiredPermissionInterceptor` (registered by `RequiredPermissionWebConfiguration`) calls `TenantAuthorizationService.requirePermission` in `preHandle`, before header/body/path-variable binding. Denials are audited (`AUTHORIZATION_DENIED` with the required permission) and mapped to 403 by the existing controller exception handlers. Authorized callers keep the existing 400 contract.

Covered: AI recommendation and draft endpoints (`FOLLOWUP_EVALUATE`; `MESSAGE_DRAFT` + `FOLLOWUP_EVALUATE`), customers (create/update/archive), contact policy (consent, do-not-contact), follow-up (tenant policy, customer policy, manual follow-up, dismissal, snooze), purchases (record/correct/void), memberships (invite/role/revoke). `POST /api/tenants` is authentication-only and unchanged.

## Evidence
`AuthorizationBeforeValidationIntegrationTest` (22 cases, full HTTP stack with Testcontainers PostgreSQL): VIEWER/OPERATOR requests with missing `If-Match`/`Idempotency-Key`, malformed JSON, invalid time zone or unknown channel return 403 and record an authorization denial with the expected permission.

Local end-to-end run (`./scripts/dev-env.sh up --seed`, VIEWER/OPERATOR/OWNER sessions through the BFF, synthetic data):

- `issue-162-evidence/http-results.txt`: 21 unauthorized + malformed requests return 403; 6 authorized + malformed requests keep the 400 contract; valid create/update still succeed (29/29 PASS).
- `issue-162-evidence/audit-rows.txt`: one `AUTHORIZATION_DENIED` / `INSUFFICIENT_PERMISSION` row per denied request, with the required permission, no duplicates.
- `./gradlew check` passes.
- Frontend/BFF: no code depends on a 400 for unauthorized callers (`apiClient.ts` / `httpClient.ts` map any 403 to the generic access-denied message; the 400 handlers in `WorkspaceSelector`/`NoMembershipsView` belong to `/api/tenants`, unchanged). Manual UI check as VIEWER: read-only notices and the AI assistant's "role not allowed" message render normally and the session stays active.
