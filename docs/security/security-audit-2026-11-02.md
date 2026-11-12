# Security Audit — 2026-11-02

| | |
|---|---|
| **Scope** | Whole repository: `backend/` (Spring Boot BFF, 423 main classes, 33 Flyway migrations), `frontend/` (React SPA), `infra/docker/`, `compose.yaml`, `scripts/`, `.github/` |
| **Audited revision** | `a328848` (branch `140-reverify-80c7ab7`). Its tree is identical to `main` at `c4b62d1` |
| **Method** | Static review of every module and trust boundary, then dynamic testing against an isolated local stack with curl, `psql` and the in-app browser |
| **Data used** | Synthetic only: tenants "Audit Tenant A/B", realm test users plus one extra synthetic user `auditattacker`, canary webhook secrets |
| **Live providers** | None. AI provider disabled, outbound messaging kill switch off, no Meta Graph calls |

## 1. Executive summary

The core security model holds under attack. Every attempt at cross-tenant access, privilege escalation, CSRF, stored XSS,
SQL injection, webhook forgery or secret leakage failed. Database Row Level Security, the signed tenant context and the
runtime role's privileges also held when tested directly with `psql`.

**No Critical or High findings.** The findings below are about **availability**, **session lifetime** and **defense in
depth**. Most are cheap to fix.

| Severity | Count | Findings |
|---|---|---|
| Critical | 0 | — |
| High | 0 | — |
| Medium | 5 | M1 to M5 |
| Low | 14 | L1 to L14 |
| Info | 11 | I1 to I11 |

Fix these first:

1. **M1**: an anonymous client can exhaust backend memory through the webhook. 600 forged 4 MB POSTs raised backend memory from 0.7 GiB to 4.2 GiB.
2. **M2**: every anonymous request creates a server-side session that lives for 30 minutes (200 of 200 requests).
3. **M3**: disabling a user in Keycloak does not end their Dokene session. The disabled user could still read and write data, and sessions have no absolute lifetime.
4. **M4**: the runtime container holds the migration credentials, which can delete the append-only audit log.

## 2. Test environment

Other agents were using the standard ports (8080/5173), so the audit used a separate Compose project:

- **Compose project:** `dokene-audit`, with its own image tags (`dokene-*:audit`).
- **Host and ports:** host `127.0.0.1`, which also keeps cookies apart from `localhost` stacks. BFF on `:38080`, Keycloak on `:38081`, SPA on `:35173`, PostgreSQL on `:35432`.
- **Overrides:** a copy of the realm with the extra redirect URIs, and a copy of `nginx.conf` with `Host 127.0.0.1:38080`.
- **Backend settings:** `DOKENE_AI_PROVIDER` blank. Local secret source with canary `APP_SECRET` and `WEBHOOK_VERIFY_TOKEN` values.
- **Fixtures:**

  | Tenant | Members |
  |---|---|
  | A | `testuser` OWNER, `testoperator` OPERATOR, `testviewer` VIEWER |
  | B | `testoperator` OWNER |

  `auditattacker` has no membership. Each tenant has one customer, with phone, notes and a purchase.

The override and the helper scripts are kept outside the repository (session scratchpad). Section 5 records them so the
setup can be rebuilt.

---

## 3. Findings

Each finding is tagged **Confirmed** (reproduced dynamically), **Static** (code evidence, not exercised) or **Latent**
(harmless with the current configuration, but becomes exploitable with a documented configuration change).

### Medium

#### M1. The unauthenticated webhook buffers up to 4 MiB per request before any cheap rejection: memory exhaustion — Confirmed

- **Status:** fixed by [#306](https://github.com/stevdrey/dokene/issues/306); see [the verification record](../verification/issue-306-webhook-resource-bounds-verification.md).

- **Where:**
  - `backend/src/main/java/io/github/stevdrey/dokene/integration/whatsapp/webhook/WhatsAppWebhookController.java:70-72`
  - `.../webhook/verify/BoundedWebhookBodyReader.java`, which calls `readNBytes(maxBodyBytes + 1)`
  - `.../webhook/verify/WebhookSignatureVerifier.java`
  - `application.yml:70` (`max-body-bytes` 4 194 304)
- **What happens:**
  ```java
  byte[] raw = reader.read(request.getInputStream(), request.getContentLengthLong());
  List<String> signatureHeaders = Collections.list(request.getHeaders(SIGNATURE_HEADER));
  body = signatures.verify(raw, signatureHeaders);
  ```
  - The full body is read into heap **before** the code checks whether `X-Hub-Signature-256` is present and well formed, or whether an app secret is configured at all.
  - A forged but well-formed header also forces an HMAC over the whole 4 MiB.
  - Every rejection writes a `WARN` log line, so the attack also floods the logs.
- **Evidence** (audit stack, 150 concurrent clients, 600 POSTs of 4 000 000 bytes with an all-zero signature):

  | Moment | Backend memory |
  |---|---|
  | Before | 714 MiB |
  | During | 2.61 GiB (CPU 572 %) |
  | After | 4.25 GiB |

  All 600 requests returned `403`.
- **Impact:**
  - The route is anonymous and internet-facing by design, since Meta must reach it.
  - The image runs with `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` (`infra/docker/backend/Dockerfile:34`). In a container with a realistic 1–2 GiB limit, a short burst is enough to cause an out-of-memory error and restart the backend. Every tenant loses service.
  - nginx buffers the body first, which removes slow-drip variants but does not reduce the heap cost.
- **Fix:**
  1. Before reading the body, refuse when the app-secret reference is unset, or when the signature header is not exactly one header matching `sha256=[0-9a-f]{64}`.
  2. Refuse early when `Content-Length` is greater than the maximum.
  3. Bound concurrency on `/webhooks/**`, for example with a semaphore or a dedicated small executor, or with an nginx `limit_conn`/`limit_req` zone.
  4. Throttle the rejection log line.
- **Reproduction steps:** withheld from this public report until the fix lands (see `SECURITY.md`). The maintainer has them.

#### M2. Every anonymous request creates a 30-minute server-side session: memory exhaustion — Confirmed

- **Where:**
  - `backend/.../tenant/security/TenantSecurityConfiguration.java:124-126` (`SessionCreationPolicy.IF_REQUIRED`, with the default request cache left on)
  - `:150` (`defaultSuccessUrl(postLoginRedirectUrl, true)`, so the saved request is never used)
  - `application.yml:131` (30 min idle timeout)
  - Sessions live in Tomcat's in-memory store, with no `maxActiveSessions`.
- **Evidence:**
  - Anonymous `GET /api/customers`, `/foo` and `/api/session` each return `401` with a fresh `Set-Cookie: JSESSIONID`. 200 of 200 sequential requests created distinct sessions.
  - With `Accept: application/json` no session is created, so the request-cache matcher is the cause.
  - 30 000 anonymous requests, each with a 4 KB `User-Agent` header, raised backend memory from 586 MiB to 707 MiB in 36 s. Every saved request keeps its headers for 30 minutes.
  - `GET /oauth2/authorization/dokene` also creates a session to hold the authorization request. That one is expected for OAuth, but nothing limits it either.
- **Impact:** anyone who can reach the edge can grow the heap without authenticating, and the growth lasts for the full idle timeout. It combines with M1.
- **Fix:**
  1. Disable the request cache with `http.requestCache(c -> c.disable())`. The post-login target is fixed anyway.
  2. Rate-limit `/oauth2/authorization/**` at the edge.
  3. Consider capping active sessions, or using an external session store with eviction.
- **Reproduction steps:** withheld from this public report until the fix lands (see `SECURITY.md`). The maintainer has them.

#### M3. A Dokene session outlives the identity provider: a user disabled or logged out in Keycloak keeps working, with no absolute lifetime — Confirmed

- **Status:** fixed by [#307](https://github.com/stevdrey/dokene/issues/307); see [the verification record](../verification/issue-307-session-revocation-verification.md). The SPA local-logout residual (item 4 of the fix) remains documented in ADR 0007.

- **Where:**
  - `TenantSecurityConfiguration.java:138-142`: no `oidcLogout().backChannel()`.
  - The realm sets `backchannel.logout.session.required`, but no back-channel URL is registered.
  - `application.yml:131`: idle timeout only, with no absolute session lifetime.
  - ID and access token expiry is never checked again after login.
- **Evidence:**
  1. `auditattacker` logged in.
  2. Through the Keycloak admin API, the user was **disabled** (`PUT enabled=false`) and **all their Keycloak sessions were logged out** (`POST /users/{id}/logout`).
  3. The existing Dokene session still answered `GET /api/customers` → `200` and `POST /api/customers` → `201`, which created a customer.
- **Impact:**
  - Offboarding an employee in the IdP does not cut off their open sessions. A stolen session cookie keeps working for as long as it is used at least every 30 minutes, which can be forever.
  - Only revoking the Dokene membership cuts access. That part works and takes effect immediately: `403` right after the revoke.
  - Related documented residual: the SPA logout is local only (`frontend/src/features/auth/SessionContext.tsx:119` never sends `provider=true`), so on a shared device the next login is silent.
- **Fix:**
  1. Implement OIDC back-channel logout: `http.oidcLogout(l -> l.backChannel(...))` plus the client's `backchannel.logout.url`.
  2. Enforce an absolute session lifetime, for example 8–12 h, stored at authentication time and checked in a filter.
  3. Optionally, refresh or check the token periodically and invalidate the session when the refresh token is rejected.
  4. Consider `provider=true` as the default SPA logout.

#### M4. The runtime process holds the migration credentials and the tenant-context signing key — Confirmed

- **Status:** fixed by [#308](https://github.com/stevdrey/dokene/issues/308); see [the verification record](../verification/issue-308-migration-credential-separation-verification.md). The backend still needs the signing key to sign capabilities; that residual risk is documented in ADR 0003.

- **Where:**
  - `application.yml:23-27` (`spring.flyway.user/password` from `DOKENE_DB_MIGRATION_*`).
  - `compose.yaml` backend environment (`DOKENE_DB_MIGRATION_PASSWORD`, `DOKENE_TENANT_CONTEXT_SIGNING_KEY`).
  - Every table has a `FOR ALL TO dokene_migration USING (true)` policy, for example `V4`.
  - The audit log is append-only only because of the runtime role's grants. No trigger blocks `UPDATE` or `DELETE`.
- **Evidence:**
  - `docker exec dokene-audit-backend-1 env` lists `DOKENE_DB_MIGRATION_PASSWORD` and `DOKENE_TENANT_CONTEXT_SIGNING_KEY`.
  - As `dokene_migration`, `BEGIN; DELETE FROM dokene.audit_events; ROLLBACK;` succeeds.
  - As `dokene_runtime`, both `UPDATE` and `DELETE` on `audit_events` are refused (`permission denied`).
- **Impact:**
  - ADR 0003 says the migration and runtime credentials are strictly separated. In practice the separation protects only against SQL issued as `dokene_runtime`, not against a compromise of the process.
  - Any RCE, SSRF to `/proc/self/environ`, heap dump or environment disclosure in the backend gives a role that can:
    - read every tenant;
    - rewrite or erase the audit trail;
    - read the signing keys and forge tenant contexts;
    - drop RLS policies.
- **Fix:**
  1. Run migrations as a separate one-shot job or init container with its own credentials, and set `spring.flyway.enabled=false` in the long-running service.
  2. Add a `BEFORE UPDATE OR DELETE` trigger on `audit_events` that raises an exception for every role, with a documented break-glass procedure.
  3. Deliver the signing key through the secret resolver or a mounted file rather than an environment variable.

#### M5. GitHub Actions are pinned to mutable tags, not commit SHAs — Static

- **Where:**
  - `.github/workflows/security.yml:23` (`gitleaks/gitleaks-action@v3`) and `:34` (`aquasecurity/trivy-action@v0.36.0`)
  - `backend.yml:29`, `codeql.yml:39` and `dependency-check.yml:45` (`gradle/actions/setup-gradle@v6`)
  - all `actions/*@vN` steps
- **Impact:**
  - Whoever controls a tag can run code in CI. That code receives the job token, and in `dependency-check.yml:58` also `secrets.NVD_API_KEY`.
  - Retagging attacks on popular actions are a known attack pattern.
  - **Unverified:** the auditing agent recalled a tag-hijack incident involving `trivy-action` in early 2026. It has not been confirmed. Check that the pinned release is genuine.
- **Mitigating factors:**
  - No `pull_request_target` trigger.
  - Least-privilege `permissions:` in every workflow.
  - No `${{ github.event.* }}` interpolated into `run:`.
  - Fork PRs receive no secrets.
- **Fix:** pin every third-party action to a full 40-character SHA, with a version comment. Dependabot's `github-actions` ecosystem (`.github/dependabot.yml`) keeps SHA pins updated.

### Low

#### L1. Any authenticated user can grow the global audit table without limit — Confirmed

- **Where:** `TenantContextRequestFilter.java:167` calls `auditRecorder.authorizationDenied(TENANT_READ, NO_TENANT_CONTEXT)` for every missing, malformed or foreign `X-Tenant-Id`. `TenantController.getWorkspace` does the same. These paths have no rate limit, and `audit_events` is never purged.
- **Evidence:** 1 000 requests from `auditattacker` with `X-Tenant-Id: <tenant B>` added exactly 1 000 rows where `tenant_id IS NULL`.
- **Impact:** storage growth, and real denials buried in noise.
- **Fix:** coalesce repeated denials per (identity, reason, window), or put a per-identity token bucket in front of the denial audit.

#### L2. Adding a member creates an ACTIVE membership without the invitee's consent, and a revoked member can never be re-added — Confirmed

- **Status:** fixed by [#317](https://github.com/stevdrey/dokene/issues/317); see [ADR 0031](../adr/0031-membership-invitation-acceptance-and-audited-reactivation.md) and [the verification record](../verification/issue-317-membership-invitation-acceptance-verification.md). An invitation is now `INVITED` until the invitee accepts it, and a revoked membership is recovered through an audited reactivation that returns it to `INVITED` (V37).

- **Where:** `backend/.../tenant/application/MembershipService.java:74` (`TenantMembership.createActive(...)`). The `INVITED` status exists but is unused.
- **Evidence:**
  - The OWNER of tenant A posted `{"identityId":"<auditattacker>","role":"ADMIN"}` and got `201 status ACTIVE`.
  - `auditattacker`'s `GET /api/tenants` immediately listed "Audit Tenant A" as ADMIN.
  - After a revoke, adding the same identity again returns `409`.
- **Impact:**
  - Identity UUIDs are visible to every co-member through `GET /api/memberships`, VIEWER included.
  - A hostile admin can therefore push a user into a look-alike workspace, for example one named the same as the user's real one (see L3), which is a phishing or confusion vector.
  - The permanent `409` is an operability problem: a mistaken revoke cannot be undone.
- **Fix:** use `INVITED` until the invitee accepts. Allow a revoked membership to be reactivated through an explicit, audited transition.

#### L3. Workspace self-provisioning is open and unlimited in the shipped Compose configuration — Confirmed

- **Where:**
  - `compose.yaml:169` and `.env.example`: `DOKENE_PROVISIONING_ENABLED=true` by default.
  - `tenant/application/DefaultProvisioningAuthorizationPolicy.java:39-40` returns `true` when the allowlist is empty.
  - `WorkspaceProvisioningService` has no per-identity cap.
- **Evidence:** `testviewer` (a VIEWER elsewhere) provisioned a new workspace named "Audit Tenant A", the same name as the real tenant A, and became its OWNER (`201`).
- **Impact:**
  - Any identity in the IdP can create unlimited tenants, which means resource abuse and look-alike workspace names.
  - The `application.yml` default is `false`, so this is a deployment-configuration risk.
- **Fix:** cap workspaces per identity, and make a non-empty allowlist a startup requirement whenever provisioning is enabled outside local profiles.

#### L4. The runtime role can call cross-tenant maintenance functions without any capability — Confirmed (callable)

- **Status:** fixed by [#313](https://github.com/stevdrey/dokene/issues/313); see [the verification record](../verification/issue-313-maintenance-function-capabilities-verification.md). The three sweeper and recovery functions now require a signed `maintenance` capability and clamp their cutoffs to the database clock (V35). `resolve_verified_whatsapp_tenant` is unchanged by design: it is a point lookup by an exact phone-number id, returns one tenant id, writes nothing, and serves the unauthenticated webhook before any tenant exists (ADR 0030 §3).

- **Where:**
  - `V29__create_provider_webhook_events.sql`: `purge_webhook_events(processed_before, max_rows)` and `find_due_webhook_events`.
  - `V26__find_expired_dispatch_claims.sql`: `find_expired_dispatch_claims`.
  - `V24__verify_whatsapp_phone_number_ownership.sql`: `resolve_verified_whatsapp_tenant`.
- **Evidence:** as `dokene_runtime` with no tenant context, the following all execute:
  - `SELECT dokene.purge_webhook_events('infinity', 5000)`
  - `find_expired_dispatch_claims('infinity', NULL, 500)`
  - `find_due_webhook_events('infinity', 10)`

  They returned 0 rows only because the audit stack had no such data.
- **Impact:**
  - Through a future SQL injection, or a compromised runtime credential, an attacker could purge every tenant's processed webhook rows, which weakens replay deduplication.
  - The attacker could also list (tenant, message, attempt) ids across tenants.
  - No SQL injection was found (see §4).
- **Fix:** clamp the cutoffs inside the functions (for example `LEAST(p_processed_before, now() - retention)`), or require a signed system-scope capability as the tenant-scoped functions do.

#### L5. The runtime role has full DML on `tenant_memberships` — Static

- **Status:** fixed by [#315](https://github.com/stevdrey/dokene/issues/315); see [the verification record](../verification/issue-315-runtime-membership-privileges-verification.md). V36 revokes `DELETE` and table-wide `UPDATE`, grants `UPDATE` only on `(role, status, updated_at, version)` and drops the unused delete policy. `role` stays updatable because role changes are a supported workflow, so the `role='OWNER'` case remains limited to the caller's own tenant by RLS and to application authorization; `identity_id` can no longer be rewritten.

- **Where:** `V1__*.sql:36` grants `SELECT, INSERT, UPDATE, DELETE ON dokene.tenants, dokene.tenant_memberships`. V16 later narrowed `tenants` to column-level UPDATE with no DELETE, but `tenant_memberships` was not narrowed.
- **Impact:** defense in depth only. An SQL injection inside a member's request could set `role='OWNER'` or rewrite `identity_id` within that tenant at the database layer. The code never deletes memberships, since revocation is a status change.
- **Fix:** `REVOKE DELETE` and grant `UPDATE` only on `(role, status, updated_at, version)`.

#### L6. Tenant-context signing keys are never retired — Static

- **Status:** fixed by [#324](https://github.com/stevdrey/dokene/issues/324); see [the verification record](../verification/issue-324-signing-key-retirement-verification.md) and the rotation runbook in the [RLS recipe](../architecture/tenant-isolation-rls-recipe.md#key-rotation-lifecycle). The migration job gives every key other than the configured one a retirement deadline (database clock plus a bounded grace, default `1h`) and records it `RETIRED` once past; the verifiers enforce the deadline themselves (V39), and a retired key id is never reactivated.

- **Where:**
  - `TenantContextSigningKeyCallback.java:56-62` only upserts the configured key as `ACTIVE`. No code writes `RETIRED`, which appears only in a CHECK constraint.
  - Verification accepts any `ACTIVE` key (`V3`, `V19`).
- **Evidence:** the table contains `default|ACTIVE`. After a key-id change, the old key would stay `ACTIVE`.
- **Impact:** a key that leaks once can forge contexts forever, even after a rotation.
- **Fix:** on startup, mark every key other than the configured one `RETIRED` after a grace period, and document the rotation in a runbook.

#### L7. A direct backend request reflects its `Host` header into the OAuth `redirect_uri` — Confirmed (mitigated by Keycloak)

- **Evidence:** `curl -H 'Host: evil.example' http://127.0.0.1:38080/oauth2/authorization/dokene` returns a `Location` containing `redirect_uri=http://evil.example/login/oauth2/code/dokene`. Keycloak then refuses it: `Invalid parameter: redirect_uri`, `400`.
- **Impact:** none today, because Keycloak matches redirect URIs exactly. A wildcard redirect URI added later would turn this into authorization-code theft. Through nginx, `Host` is pinned, so this is direct-access only.
- **Fix:** set an absolute `redirect-uri` per environment instead of `{baseUrl}`, or reject requests whose `Host` is not in an allowlist.

#### L8. nginx passes client-supplied `X-Forwarded-*` and `Forwarded` headers to the backend — Latent

- **Where:** `infra/docker/frontend/nginx.conf:15-27` and `:33-41` set only `Host` and `Connection`.
- **Evidence:** with the default `SERVER_FORWARD_HEADERS_STRATEGY=none`, a forged `X-Forwarded-Host: evil.example` through the SPA proxy has no effect: `redirect_uri` stays `http://127.0.0.1:38080/...`.
- **Impact:**
  - `.env.example` advises `framework` behind a proxy, and says the edge must strip these headers. The repository's own edge does not strip them.
  - Under `framework`, an attacker could control `{baseUrl}`, `isSecure()` and HSTS.
- **Fix:** add `proxy_set_header Forwarded "";` and set `X-Forwarded-For/Proto/Host/Port` from `$remote_addr`, `$scheme`, `$host` and `$server_port`.

#### L9. The Keycloak realm leaves hardening to defaults — Confirmed

- **Where:** `infra/docker/keycloak/import/dokene-realm.json`
- **Evidence** (admin API and probes):
  - `passwordPolicy: null`.
  - The `dokene-bff` client has no `pkce.code.challenge.method`. An authorization request **without** `code_challenge` gets the login form (`200`), so PKCE is not enforced on the server. Spring does send S256.
  - All realm users share `DOKENE_TEST_USER_PASSWORD`, which defaults to `testpassword` (`compose.yaml:74`), and that includes the OWNER.
- **Working as intended:**
  - Brute-force protection: `failureFactor 30`, temporary lockout, `MULTIPLE` strategy. A lockout was observed and the correct password was refused while locked.
  - `sslRequired: external`.
  - No implicit flow, no direct access grants. A password grant was refused with `unauthorized_client`.
  - Confidential client, exact redirect URIs, registration off.
- **Impact:** local-only today, because the ports are bound to loopback. The realm is the natural template for any shared or staging deployment.
- **Fix:** set `pkce.code.challenge.method=S256` on the client, add a password policy, and generate per-user random passwords in `dev-env.sh`.
- **Status:** fixed by [#314](https://github.com/stevdrey/dokene/issues/314); see [the verification record](../verification/issue-314-identity-provider-hardening-verification.md). The realm requires S256 PKCE on `dokene-bff`, carries `length(12) and notUsername and notEmail`, and each test user has its own generated password; `scripts/keycloak-apply-realm-settings.sh` brings existing realms up to date on `dev-env.sh up`. The realm stays a local template: a shared deployment still needs its own realm process (password history, breached password blocklist, MFA for OWNER), noted in `infra/docker/README.md`.

#### L10. OpenAI calls set no output-token cap — Static

- **Where:** `backend/.../ai/provider/openai/OpenAiResponsesApiAdapter.java:565` (draft) and `:655` (recommendation) build `ResponseCreateParams` without `maxOutputTokens`.
- **Impact:**
  - The JSON schema `maxLength`s are checked only after generation, so each call can run to the 15 s timeout.
  - Customer notes, which operators control, are sent to the model and can ask for verbose output.
  - Rate limits bound how many calls run, not what each one costs (60 per tenant and 20 per actor per minute, in memory and per instance).
- **Fix:** set `maxOutputTokens` from the schema's maximum size plus headroom. Consider a per-tenant monthly token budget.
- **Status:** fixed by [#316](https://github.com/stevdrey/dokene/issues/316); see [the verification record](../verification/issue-316-openai-output-token-ceiling-verification.md). The ceiling is set from observed output and the timeout rather than the schema maximum; the record explains why. The per-tenant token budget is tracked in [#346](https://github.com/stevdrey/dokene/issues/346).

#### L11. The OpenAI base URL is not validated — Static (configuration)

- **Where:** `backend/.../ai/provider/openai/OpenAiConfiguration.java:30-31` accepts any `baseUrl`, plain `http://` included. Compare `integration/whatsapp/GraphApiProperties.java`, which requires HTTPS on an allowlisted host.
- **Impact:** a typo or a tampered configuration sends the API key and customer PII in cleartext to an arbitrary host.
- **Fix:** apply the same validation as `GraphApiProperties`: HTTPS, an allowlisted host or loopback, and no userinfo, query or fragment.
- **Status:** fixed by [#322](https://github.com/stevdrey/dokene/issues/322); see [the verification record](../verification/issue-322-openai-endpoint-validation-verification.md) and [ADR 0016](../adr/0016-openai-responses-api-adapter-with-structured-outputs.md) §6. The base URL is validated when the properties are bound, so a rejected value stops startup before any client exists: `https` on `api.openai.com`, an exact loopback test server, or an `https` host in `dokene.ai.openai.additional-trusted-hosts`, never with user info, query or fragment. Unlike the Graph policy, `*.localhost` is not accepted (see I3). The client gets a URL rebuilt from the validated parts and no longer follows redirects, which the SDK did by default.

#### L12. Admin endpoints that call the provider are not throttled — Static

- **Where:**
  - `template/application/TemplateRegistryService.java:120-161`: `POST /api/templates/{intent}/approval-refresh` has no limiter.
  - `integration/application/WhatsAppIntegrationService.java:281-286`: an `Unavailable` ownership proof costs no `MappingRejectionLimiter` budget.
- **Impact:** a member with `TEMPLATE_WRITE` or `INTEGRATION_MANAGE` can loop these calls. Each one makes up to 5 Graph GETs with the tenant's token. The loop burns the tenant's Meta quota, which can block real sends, and ties up request threads.
- **Fix:** add a per-tenant token bucket to both endpoints.
- **Status:** fixed by [#321](https://github.com/stevdrey/dokene/issues/321); see [the verification record](../verification/issue-321-admin-provider-call-limits-verification.md). Each endpoint now spends a budget of provider calls per tenant and per member in a sliding window (defaults 30 per tenant and 20 per member in 15 minutes), whatever the provider answers, unavailable answers included. Template refreshes are charged per Graph page they read and not at all when no request was sent. A spent budget is `429 RATE_LIMITED` with `Retry-After`, makes no provider call and is audited as `AUTHORIZATION_DENIED` with reason `PROVIDER_CALL_LIMITED` at most once per member per window. A sliding window was used instead of a token bucket because it gives the same hard bound per window and reuses the existing `SlidingWindow` primitive.

#### L13. Builds are not reproducible or verified — Static

- **Where:**
  - Base images are pinned by tag, not digest: `eclipse-temurin:26-*`, `node:24-alpine`, `nginxinc/nginx-unprivileged:1.29-alpine`, `postgres:17-alpine`, `alpine/socat`, Keycloak.
  - `.github/dependabot.yml` has no `docker` ecosystem.
  - `backend/gradle/wrapper/gradle-wrapper.properties` has no `distributionSha256Sum`, and there is no `gradle/verification-metadata.xml`.
- **Fix:** pin `@sha256:` digests and add Dependabot `docker` entries. Add `distributionSha256Sum`, and consider Gradle dependency verification.

#### L14. The PostgreSQL and Keycloak containers lack the hardening the other services have — Static

- **Status:** fixed by [#319](https://github.com/stevdrey/dokene/issues/319); see [the verification record](../verification/issue-319-container-hardening-verification.md). Both services now drop every capability, set `no-new-privileges` and have a read-only root filesystem with listed writable paths; PostgreSQL starts as its unprivileged user.

- **Where:** `compose.yaml:1-87`. The postgres and keycloak services have no `cap_drop: [ALL]`, `no-new-privileges` or `read_only`. The backend, frontend and oidc-bridge services have all three.
- **Fix:** apply the same options. A writable volume and tmpfs are still needed where required.

### Informational

| # | Observation | Status | Where |
|---|---|---|---|
| I1 | `OpenAiProviderProperties` is a record, so its default `toString()` prints `apiKey`. Nothing logs it today. The other secret holders redact their `toString`. | Static, latent | `ai/provider/openai/OpenAiProviderProperties.java:11-17` |
| I2 | Prompt sanitizing escapes only `<` and `>`. Newlines in notes can forge prompt lines inside `<untrusted_customer_data>`. Output stays advisory: the gate, human approval and the send guards all apply. | Static | `OpenAiResponsesApiAdapter.java:702-707` |
| I3 | The Graph base-URL loopback allowance accepts `*.localhost` over HTTP. Whether that resolves to loopback depends on the resolver. | Static | `GraphApiProperties.java` |
| I4 | The webhook verify GET echoes `hub.challenge` verbatim (`<script>` returned as-is). It is safe because the response is `text/plain` with `nosniff` and CSP `default-src 'none'`. It could still be restricted to digits. | Confirmed, not exploitable | `WhatsAppWebhookController` |
| I5 | `TenantScopedRequestMatcher` exempts `/api/account/**`, `/api/profile/**` and `/api/me/**`, which no controller uses yet. A future controller there would silently skip tenant enforcement. | Static | `tenant/security/TenantScopedRequestMatcher.java` |
| I6 | HSTS is sent only on secure requests. The session cookie is `JSESSIONID`, not `__Host-` prefixed. There is no concurrent-session limit. | Static | `TenantSecurityConfiguration` |
| I7 | SQL signature checks compare with `<>`, not in constant time. It is not practically exploitable over SQL. | Static | `V3`, `V19` |
| I8 | SECURITY DEFINER `search_path` has no trailing `pg_temp`. This is mitigated: the runtime role cannot create temp tables (confirmed: `permission denied to create temporary tables`). | Confirmed mitigated | migrations |
| I9 | Webhook replay after the 30-day dedup retention can only produce a non-applied out-of-order event. | Static | `WebhookIngestionService` |
| I10 | The rate limiters (AI, mapping rejections) are in memory and per instance, so they reset on restart and multiply with the number of replicas. This is already documented. | Static | `ratelimit`, `followup` |
| I11 | Brute-force lockout in Keycloak lets anyone who knows a username lock that account temporarily. This is the expected trade-off. | Confirmed | realm |

---

## 4. Controls verified (attacks that failed)

All results come from the audit stack. "A" and "B" are the two synthetic tenants, `CA` and `CB` their customers.

### Tenant isolation and IDOR

| Test | Result |
|---|---|
| A-context access to tenant B's customer: GET, PUT and DELETE, contact policy, history, purchases, `/last`, follow-up policy and eligibility | `404` on every route |
| Consent and do-not-contact writes on B's customer, and on B's contact id through A's own customer | `404` (with a valid `If-Match`) |
| A's customer path with B's purchase id: GET, history, void | `404` |
| Random message id: GET and approve | `404` / `400` |
| Eligibility for a foreign contact id vs a random id | Same answer (`CONTACT_NOT_ACTIVE`), so no existence oracle |
| `X-Tenant-Id` for a tenant without membership, missing, duplicated, or malformed | `403` / `403` / `400` / `400` |
| Non-member `GET /api/tenants/{A}` | `403`. `GET /api/tenants` → `[]` |
| Membership revocation | Takes effect immediately (`403` on the next request) |
| Idempotency key reused by another identity, or across tenants | Scoped correctly: a new resource is created and the other identity's response is not replayed |

### Authorization (RBAC)

| Test | Result |
|---|---|
| VIEWER: create customer, invite member, self-promote, change tenant policy, toggle outbound | `403` |
| OPERATOR: self-promote, invite, revoke the OWNER, change tenant follow-up policy | `403` |
| OWNER: invite someone as OWNER, demote self (last owner) | `400` |

### Session, CSRF, CORS and browser

| Test | Result |
|---|---|
| Unsafe method without a CSRF token, with another session's token, or as a `text/plain` simple request | `403` |
| `POST /logout` without a token | `403`. `GET /logout` → `404` |
| CORS preflight from `http://evil.example`, or with `Origin: null` | `403`, no ACAO header |
| CORS from the configured SPA origin | ACAO set to that exact origin, with credentials |
| Session fixation | Session id rotated on login |
| Logout, then reusing the old cookie | `401` (server-side invalidation) |
| Stored XSS: customer name `<img src=x onerror=…><svg/onload=…>`, notes with `<script>`, `javascript:` and template-injection payloads, viewed in the SPA list and detail (in-app browser, `alert` hooked) | Rendered as text: no injected elements and no `alert` call |
| Cookie exposure | `document.cookie` is empty (HttpOnly). `sessionStorage` holds only the active tenant id. No `localStorage` |
| Security headers on the SPA and the API | Strict CSP (no `unsafe-inline`), `frame-ancestors 'none'`, `X-Frame-Options: DENY`, `nosniff`, Referrer-Policy and Permissions-Policy present. API responses are `no-store` |

### Input handling

| Test | Result |
|---|---|
| SQL injection in the `name` filter and in `cursor`, `limit=999999`, malformed JSON, malformed UUID | `200 []` (escaped) or a generic `400` with a constant message. No stack traces or internals |
| Mass assignment | Request DTOs are records without tenant, status or version fields. Version comes only from `If-Match` |

### Webhook

| Test | Result |
|---|---|
| Verify GET with a wrong token | `403` |
| POST with no signature, a wrong signature, or two signature headers | `403` |
| Valid signature with a supplied `tenantId` member, duplicate keys, or 5 000-deep nesting | `400` |
| Webhook with a browser session cookie and no CSRF token | Signature-gated only. The webhook chain is stateless by design |
| Other `/webhooks/*` paths | `403` |

### Database (`psql` as `dokene_runtime`)

| Test | Result |
|---|---|
| Role attributes | `NOSUPERUSER`, `NOBYPASSRLS` |
| Reads without a context | 0 rows from `customers` and `tenants` |
| Setting a raw GUC tenant id without a signature | Still 0 rows |
| `flyway_schema_history`, `tenant_context_signing_keys`, `oidc_identity_mappings` | `permission denied` |
| `CREATE TEMP TABLE`, `CREATE TABLE dokene.*` | `permission denied` |
| `UPDATE` or `DELETE` on `audit_events` | `permission denied` |

### Logs

After all tests, the backend log contains none of the following: the canary secrets, phone numbers, notes or purchase
text, the XSS payload, the password, `JSESSIONID` or CSRF tokens.

### Reviewed statically and found sound

- **Authentication:**
  - OIDC `(iss, sub)` identity linking only.
  - Fixed post-login and failure redirects (no open redirect).
  - PKCE S256 and state sent by the client.
  - Actuator fully disabled.
  - Generic error bodies.
- **Tenant isolation in code:**
  - Tenant context per request through a signed SECURITY DEFINER resolver, held in a `ScopedValue`.
  - Context is transaction-local and reset when a pooled connection closes.
- **Database schema:**
  - FORCE RLS on all 27 tenant tables.
  - Composite tenant foreign keys.
  - Parameterized SQL everywhere, LIKE input escaped.
- **Outbound messaging:**
  - Idempotency, `If-Match`, customer and message locks, a single `STARTED` claim.
  - Approval fingerprint recomputed at send.
  - Kill switches re-read at claim time.
  - Template parameters from a closed vocabulary, with JSON built through `ObjectNode`.
- **Provider calls (SSRF and secrets):**
  - Graph ids `^[0-9]{5,32}$`, redirects disabled, 256 KiB response cap.
  - `ResolvedSecret` redaction.
- **Frontend:**
  - No `dangerouslySetInnerHTML`, `innerHTML`, `eval`, `window.open` or `href` from data.
- **Supply chain:**
  - npm with `ignore-scripts=true`, exact versions and integrity hashes. `npm audit`: 0 vulnerabilities.
  - `.env` untracked, and no secrets in tracked files.
  - CI has no `pull_request_target` and no untrusted interpolation.

## 5. Reproducing the audit stack

```bash
# Override (kept outside the repo): own project name, image tags and 127.0.0.1 ports.
export COMPOSE_PROJECT_NAME=dokene-audit KEYCLOAK_PORT=38081 AUDIT_DIR=<dir>
docker compose -f compose.yaml -f "$AUDIT_DIR/compose.audit.yaml" up -d --build --wait
```

`compose.audit.yaml` does the following:

- Remaps the ports to `127.0.0.1:{35432,38081,38080,35173}`.
- Mounts a realm copy that adds `http://127.0.0.1:38080/login/oauth2/code/dokene` and `http://127.0.0.1:35173/`.
- Mounts an `nginx.conf` copy with `proxy_set_header Host 127.0.0.1:38080`.
- Sets these backend variables:
  - `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_DOKENE_ISSUER_URI=http://127.0.0.1:38081/realms/dokene`
  - CORS origin and post-login URLs for `http://127.0.0.1:35173`
  - `DOKENE_AI_PROVIDER=""`
  - a local secret source with canary `APP_SECRET` and `WEBHOOK_VERIFY_TOKEN` entries

## 6. Recommended remediation order

1. **M1 and M2** (availability): small, local changes in `WhatsAppWebhookController` and `TenantSecurityConfiguration`, plus edge rate limits.
2. **M3** (session lifetime): back-channel logout and an absolute session timeout. This needs an ADR 0007 addendum.
3. **M4** (credential separation): separate the migration job from the runtime service and add an audit immutability trigger. This needs an ADR 0003 update.
4. **M5, L13** (supply chain): SHA- and digest-pinning, with Dependabot managing the pins.
5. **L1 to L6**: database and membership hardening, batched into one migration PR where possible.
6. The remaining Low and Info items as opportunistic hardening.
