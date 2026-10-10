# Issue 119 verification: regression revalidation after Phase 2 AI changes

Campaign: #118. Suite: #119. This record has five parts: the **verification of #162, #163 and #164 on `main` @ `d04eb0b`** (latest), the verification of #159 on `f46dc33`, the full re-execution on `a97dc74`, the previous re-execution on `90045a1` and the initial run on `2e0a254` (the last three kept below).

## Verification of #162, #163 and #164 on `main` @ `d04eb0b`

Fixes: #162 authorize before request binding/validation (PR #166), #163 preflight sees ports held by Docker containers (PR #167), #164 recovery of `oidc-bridge` after a failed `up` (PR #168). Synthetic data; real OpenAI for the AI-endpoint regression check. Evidence: `issue-119-evidence/verify-162-163-164-d04eb0b/`.

| Issue | Result | Evidence |
|---|---|---|
| #162 | **PASS** | 49/49 checks. A VIEWER sending malformed requests (missing `If-Match`/`Idempotency-Key`, invalid bodies) to 16 mutating endpoints now gets **403** and exactly one `AUTHORIZATION_DENIED` audit row each, with the right permission (`CUSTOMER_WRITE`, `CUSTOMER_DELETE`, `PURCHASE_WRITE`, `FOLLOWUP_WRITE`, `TENANT_UPDATE`); an OPERATOR on membership and tenant-policy endpoints gets 403 (not 400); no session -> 401. Authorized callers still get validation errors **after** authorization (400 with `field`), and valid requests still succeed. AI regression: VIEWER recommendation/draft 403; OPERATOR recommendation and draft 200 `AVAILABLE` (live OpenAI, Spanish rationale). `seed-local-qa.sh --verify` 5/5 |
| #163 | **PASS** | doctor and up name the Docker container that holds the port (`dokene-oidc-bridge-1`, `qa-port-hog`), print a hint, exit 1 and start nothing; native listeners are still detected |
| #164 | **PASS (with caveat)** | after a failed raw `docker compose up` (bridge created with 0 networks), `./scripts/dev-env.sh up` recreates it and the stack becomes healthy. Verified using a temporary QA-only override because of #171 |

### New finding (High, blocks the environment)
[#171](https://github.com/stevdrey/dokene/issues/171): the `oidc-bridge` healthcheck added in #168 (`nslookup oidc-bridge`) fails on hosts whose DNS configuration has a search domain (here `search lan`): BusyBox `nslookup` appends the domain and gets NXDOMAIN, so `./scripts/dev-env.sh up` never succeeds (3/3, including after `down`). The probe did not exist before #168. The failing probe is also the part of #164 that detects a network-less bridge, so a fix should keep a DNS-based check (for example `nslookup oidc-bridge.` or `getent hosts oidc-bridge`) rather than a loopback-only TCP probe. To continue testing I used a QA-only Compose override kept outside the repository (replacing the healthcheck with `nc -z 127.0.0.1 8081`) through `COMPOSE_FILE`; no product file was modified.

Scoped verdict: #162, #163 and #164 are fixed and verified; **#171 is open and must be fixed and retested** before the local environment can be called healthy on all hosts.

---

## Verification of #159 on `main` @ `f46dc33`

Restriction of the tenant-wide follow-up policy to OWNER/ADMIN (`TENANT_UPDATE`, PR #160). Clean stack (`./scripts/dev-env.sh up --seed`), synthetic data, direct API calls through the BFF plus a UI smoke test.

| Acceptance criterion | Result | Evidence |
|---|---|---|
| OPERATOR and VIEWER get 403 on `PUT /api/follow-up-policy`; nothing changes | PASS | 403/403; policy `[30, UTC]` and its version unchanged; a demoted OPERATOR is denied again |
| OWNER and ADMIN still succeed; ETag/If-Match semantics unchanged | PASS | OWNER 200, ADMIN 200 (testoperator promoted through `PUT /api/memberships/{id}/role` and demoted back); version advances by 1; stale If-Match 409; invalid time zone / cadence 0 -> 400 |
| OPERATOR keeps reading the tenant policy and the daily actions | PASS | GET 200 (OPERATOR and VIEWER); OPERATOR snooze, dismissal, manual completion and per-customer policy still succeed; VIEWER per-customer policy 403; queue readable |
| Denial audited with the required permission | PASS | `AUTHORIZATION_DENIED | DENIED | TENANT_UPDATE | INSUFFICIENT_PERMISSION` rows for each well-formed denied attempt; successful changes audited as `TENANT_FOLLOW_UP_POLICY_CHANGED` |
| UI unaffected | PASS | OPERATOR sees the workbench and the read-only Configuración screen (no editing controls), and completed a manual follow-up |
| Regression | PASS | `seed-local-qa.sh --verify` 5/5 |

The first run reported 4 failed checks: two audit-count expectations were my own arithmetic mistakes (3 denials and 3 successful changes are the correct counts for the requests sent), and two checks assumed that a malformed request from an unauthorized caller also returns 403. It returns **400** because required headers and the request body are bound/validated by the controller before the service-level permission check, and such attempts are not audited. This is pre-existing and cross-cutting (it also affects customers, do-not-contact and dispositions), so it is tracked separately: [#162](https://github.com/stevdrey/dokene/issues/162) (Low). Evidence: `issue-119-evidence/verify-159-f46dc33/`.

Environment findings from this session (Low, tooling): [#163](https://github.com/stevdrey/dokene/issues/163) (the `dev-env.sh` preflight does not see ports published by other Docker containers on Linux because `lsof` cannot see root-owned `docker-proxy` sockets) and [#164](https://github.com/stevdrey/dokene/issues/164) (after a failed `up`, `oidc-bridge` stays without a network until `down`).

Scoped verdict for #159: **PASS**, with #162, #163 and #164 open as Low findings.

---

## Full re-execution on `main` @ `a97dc74`

Includes everything from `90045a1` plus the fixes for #155 (`oidc-bridge` owns the network namespace; `restart-backend`) and #156 (neutral session-ended message). Fresh stack in a new Compose project: `./scripts/dev-env.sh up --seed` (39 s with a warm build cache). Real OpenAI Responses API (`gpt-6-luna`, default base URL); the backend container held an established TLS connection to api.openai.com (172.66.0.243:443, read from `/proc/net/tcp`). Synthetic data only.

Environment: Fedora (Linux 7.2), Docker 29.8.2, Compose 5.6.0; postgres:17-alpine, Keycloak 26.7.3, backend (Temurin 26 JRE, Spring Boot 4.1.1, Tomcat 11.0.25, libphonenumber 9.0.40, openai-java 4.73.0, okhttp 4.12.0, Gradle 9.8.0), frontend (React 19.3.0, libphonenumber-js 1.13.14, Vite 8.3.1, nginx 1.29.8), Chrome in the in-app browser. Dependency versions are unchanged since `90045a1`.

| Case | Result | Evidence / notes |
|---|---|---|
| REG-01 clean environment, migrations, runtime role (#64, #66) | PASS | 15 Flyway versions OK; `dokene_runtime`/`dokene_migration` non-superuser, no RLS bypass; two tenants; OWNER/OPERATOR/VIEWER; OPERATOR gets 403 on the other tenant |
| REG-01 `seed-local-qa.sh --verify` (#125) | PASS | 10/10 |
| REG-01 / #155 `restart-backend` | PASS | 3/3 restarts healthy in ~13 s with different `DOKENE_AI_*` values; the original reproduction (`docker compose up -d backend` alone) now also ends healthy; live configuration restored afterwards |
| REG-02 login, logout | PASS | after logout `/api/session` is 401, no data on screen; silent re-login is the documented local-logout behavior (ADR 0007) |
| REG-02 stale callback (#68) | PASS | 302 to `/?error=login_failed`; UI shows the recoverable error alert |
| REG-02 expired-session write (#70) and two tabs; #156 | PASS | write with an invalidated session and an action after signing out in another tab both show the neutral "Tu sesión ya no está activa. Por favor inicia sesión nuevamente."; no "Acceso denegado" permission message |
| REG-02 deep-link refresh, Back/Forward | NOT APPLICABLE | the SPA has no URL routing (known limitation recorded in #57) |
| REG-03 customer/contact | PASS | CL, CR, US; duplicate 409 incl. archived record; same phone allowed in another tenant; invalid phone and name errors with `field`; stale If-Match 409; OPERATOR cannot archive (403, by design); UI: Costa Rican customer created (`+50688885544`) |
| REG-04 consent | PASS | unknown -> granted -> revoked; DNC override; revoked + cleared DNC stays `CONSENT_REVOKED`; no transfer on contact change; VIEWER 403; UI grant |
| REG-05 purchases | PASS | backdated, corrected, voided; latest valid always used; history kept; idempotent replay and key conflict; future date 400 |
| REG-06 follow-ups | PASS (partial scope) | due/overdue, explicit date, snooze, dismissal + replay, completion + replay, stale 409, invalid key 400, single completion row; tenant timezone boundary re-checked with expected values computed from the real zone (the first automated expectation depended on the time of day, see `timezone-boundary-recheck.txt`); UI: completion, dismissal and snooze. Not executed: second recurrence cycle (needs clock control) |
| REG-07 integrated journey, live OpenAI | PASS with limitation | UI: create (CR) -> consent -> two purchases -> correction -> OVERDUE -> real recommendation (Spanish rationale) + draft -> edit -> manual completion -> logout/login; list/detail/queue consistent. Customer, contact-policy, follow-up policy, purchase and queue state identical before/after the AI calls; the only audit change caused by the AI calls is 2 `AI_INVOCATION_OUTCOME` (other rows in the diff are the two customers prepared between the snapshots). "Copy draft" success path BLOCKED (clipboard-write denied in the in-app browser; the app shows a clear failure message) |
| REG-08 AI dependency independence | PASS | live (REG-07), disabled (`AI_UNAVAILABLE`, clear message, dismissal executed), unreachable provider (fault injection, **not live evidence**: "assistant unavailable" + Retry, snooze executed) |
| REG-09 visual fixes | PASS | 320 px customer search (#83), 768 px shell (#82), 1024 px workbench detail (PR #111): no horizontal overflow, controls 44 px high |
| REG-10 new dependencies | PASS | versions above; images build from `main`; phone normalization and live OpenAI round trip exercised. Automated suites not re-run in this suite |

### Findings
No new defects. #155 and #156 (found in the previous re-execution) are fixed and verified; #125, #126, #127 and #153 were verified earlier and re-exercised here.

### Open design question (OBS-01): who may change the tenant-wide follow-up policy
Not a defect; kept visible for a maintainer decision.

- **Observed (re-confirmed on `a97dc74`):** `PUT /api/follow-up-policy` with an OPERATOR session returns 200 and changes the tenant cadence/time zone; a VIEWER gets 403 (`api-checks-output.txt`, lines `OBS-01`).
- **Why:** `FollowUpService.configureTenant` only requires `FOLLOWUP_WRITE`, the same permission that gates per-customer dispositions, and OWNER, ADMIN and OPERATOR all hold it (`TenantRolePermissions`). ADR 0005 lists `FOLLOWUP_WRITE` but defines no tenant-policy permission, and ADR 0012/0013 do not say who may configure tenant policy, so the current behavior is neither documented nor prohibited.
- **Impact:** the tenant time zone and default cadence change the due classification of **every** customer in the workspace and therefore the shared queue; an operator tweaking it affects all other operators. Changes are audited (`TENANT_FOLLOW_UP_POLICY_CHANGED`).
- **Options:** (A) accept and document that OPERATORs may configure it; (B) require `TENANT_UPDATE` (OWNER/ADMIN only) for the tenant-wide `PUT /api/follow-up-policy`, keeping `FOLLOWUP_WRITE` for the per-customer policy, snooze, dismissal and completion; (C) add a dedicated `FOLLOWUP_POLICY_WRITE` permission.
- **Recommendation:** (B). It is the smallest change, matches the least-privilege expectation in the security guidance, and needs an ADR 0005 note plus tests; it is a behavior change and is handled in a separate Issue/PR. Whether the UI exposes tenant-policy editing to OPERATORs was not verified in this run.
- **Status:** the maintainer chose option (B); tracked as [#159](https://github.com/stevdrey/dokene/issues/159). The UI only reads the tenant policy (`getTenantFollowUpPolicy`, GET), so the exposure is through the API.

### Live OpenAI usage in this re-execution
1 recommendation and 1 draft (UI). Campaign total: 10 provider calls (first run 2, retest 4, previous re-execution 2, this run 2); token counts are not exposed by the app; estimated cost well below US$0.05.

### Not covered / limitations
Second recurrence cycle (REG-06), clipboard copy success (REG-07), browsers other than the in-app Chrome and real devices, keyboard/screen-reader accessibility (suite #122), OPERATOR archive control visibility in the UI.

### Evidence files
[`issue-119-evidence/rerun-a97dc74/`](issue-119-evidence/rerun-a97dc74/): `api-checks-output.txt` (77 checks incl. OBS-01 info lines: 75 pass; the 2 automated date-boundary expectations were wrong for the time of day and are covered by `timezone-boundary-recheck.txt`), AI side-effect snapshots and audit counts. All sanitized (synthetic data; no cookies, tokens, passwords or API keys).

---

## Previous re-execution on `main` @ `90045a1` (historical)

Includes the fixes for #125, #126 and #127, the containerized environment (ADR 0022) and #153 (Costa Rica). Clean stack: `./scripts/dev-env.sh reset --yes && ./scripts/dev-env.sh up --seed`. Real OpenAI Responses API (`gpt-6-luna`, default base URL); the backend container held an established TLS connection to api.openai.com (162.159.140.245:443, read from `/proc/net/tcp`). Synthetic data only.

Environment: Fedora (Linux 7.2), Docker 29.8.2, Compose 5.6.0, containers: postgres:17-alpine (PG 17.x), Keycloak 26.7.3, backend (Temurin 26 JRE, Spring Boot 4.1.1, Tomcat 11.0.25, libphonenumber 9.0.40, openai-java 4.73.0, okhttp 4.12.0, Gradle 9.8.0), frontend (Node 24 build, React 19.3.0, libphonenumber-js 1.13.14, Vite 8.3.1, nginx 1.29), Chrome in the in-app browser.

| Case | Result | Evidence / notes |
|---|---|---|
| REG-01 clean environment, migrations, runtime role (#64, #66) | PASS | clean `up --seed` healthy in 54 s; Flyway 15 versions all successful; `dokene_runtime`/`dokene_migration` are not superusers and `bypassrls=f` (one check of my API script failed only because of shell quoting and was re-verified directly); two tenants; OWNER/OPERATOR/VIEWER roles; OPERATOR gets 403 on the other tenant |
| REG-01 `seed-local-qa.sh --verify` (#125) | PASS | 10/10 (25/25 on `01bcbea`) |
| REG-02 login, logout | PASS | after logout `/api/session` and `/api/customers` return 401, no data on screen; local logout leaves the Keycloak SSO session so the next login is silent (ADR 0007: provider logout is opt-in) |
| REG-02 stale callback (#68) | PASS | 302 to `http://localhost:5173/?error=login_failed`; UI shows the recoverable error alert |
| REG-02 expired-session mutation (#70) | PASS | API 401; UI write with an invalidated session shows the session-expired warning and keeps the form values. Wording says "inactivity" even when it is not: #156 |
| REG-02 two tabs | PASS | sign out in tab 2, write in tab 1 -> session-expired warning (wording: #156) |
| REG-02 refresh on deep link, Back/Forward | NOT APPLICABLE | the SPA has no URL routing (known limitation recorded in #57) |
| REG-03 customer/contact | PASS | API: CL, CR (region added in #153), US; duplicate 409 including an archived record; same phone allowed in another tenant; invalid phone -> 400 `phones[0].number` (#72); blank/161-char name -> 400 `displayName` (#127); stale If-Match 409; OPERATOR cannot archive (403, by design), OWNER archive 204; search by phone/name. UI: created a Costa Rican customer (region selector), stored as `+50688885544` |
| REG-04 consent | PASS | unknown -> granted -> revoked; DNC overrides grant; clearing DNC with revoked consent stays `CONSENT_REVOKED`; changing the phone resets consent to UNKNOWN; stale 409; VIEWER 403; UI grant |
| REG-05 purchases | PASS | backdated, corrected and voided purchases; last purchase always the latest valid; history keeps RECORDED/VOIDED; idempotent replay and key conflict; future date 400; UI backdated entry did not replace the latest purchase |
| REG-06 follow-ups | PASS (partial scope) | overdue/due/explicit date, tenant timezone date boundaries (Kiritimati/Pago_Pago), snooze (past 400, future 200, leaves queue), dismissal success + replay (201/200), completion + replay, stale 409, invalid key 400, single completion row; UI: dismissal, completion and snooze executed. Not executed: a second recurrence cycle (needs clock control) |
| REG-07 integrated journey with live OpenAI | PASS with limitation | UI: create (CR) -> consent -> two purchases -> correction -> queue (OVERDUE) -> real recommendation (Spanish rationale) + draft -> edit -> manual completion -> logout/login; list/detail/queue consistent. Business state identical before/after the AI calls; audit gained only 2 `AI_INVOCATION_OUTCOME`. "Copy draft" success path BLOCKED (clipboard-write denied in the in-app browser; the app shows a clear failure message) |
| REG-08 AI dependency independence | PASS | live (REG-07), disabled (`AI_UNAVAILABLE`, clear message, dismissal executed), unreachable provider (fault injection, **not live evidence**: "assistant unavailable" + Retry, snooze executed) |
| REG-09 visual fixes | PASS | 320 px customer search (#83), 768 px shell (#82), 1024 px workbench detail (PR #111): no horizontal overflow, controls 44 px high |
| REG-10 new dependencies | PASS | versions above; images built from `main`; backend jar and frontend bundle built inside Docker; phone normalization and live OpenAI round trip exercised. Automated suites were not re-run in this suite |

### Findings
- [#155](https://github.com/stevdrey/dokene/issues/155) (Low): recreating only the `backend` service leaves `oidc-bridge` on a dead network namespace; workaround `docker compose up -d backend oidc-bridge`.
- [#156](https://github.com/stevdrey/dokene/issues/156) (Low): session-expired message always says "inactivity".
- Previously found #125, #126, #127 and the Costa Rica gap (#153) are fixed and verified above.

### Live OpenAI usage in this re-execution
1 recommendation and 1 draft (UI). Campaign total so far: 8 provider calls (first run 2, retest 4, this run 2); token counts are not exposed by the app; estimated cost well below US$0.05.

### Not covered / limitations
Second recurrence cycle (REG-06), clipboard copy success (REG-07), browsers other than the in-app Chrome and real devices, keyboard/screen-reader accessibility (suite #122), OPERATOR archive control visibility in the UI. Observation kept open: an OPERATOR can change the tenant-wide follow-up policy (`FOLLOWUP_WRITE`); not documented as owner-only.

### Evidence files
[`issue-119-evidence/rerun-90045a1/`](issue-119-evidence/rerun-90045a1/): `api-checks-output.txt` (76 API checks: 76 pass, with the one harness quoting error re-verified), AI side-effect snapshots and audit counts, and the text of the findings. All sanitized (synthetic data; no cookies, tokens, passwords or API keys).

---

# Initial run on `2e0a254` (historical)

## Environment
Fedora (Linux 7.2), JDK 26.0.2 Temurin (build toolchain; shell default was 27), Node 24.21.0 / npm 11.19, Docker 29.8.2, postgres:17-alpine (PG 17.11), Keycloak via `compose.yaml`, backend `./gradlew bootRun` (Spring Boot 4.1.1, Gradle 9.8.0), frontend Vite 8.3.1 (React 19.3.0, TypeScript 7.0.2), Chrome in the in-app browser. AI: `DOKENE_AI_PROVIDER=openai`, model `gpt-6-luna`, empty base URL; the backend process held an established TLS connection to api.openai.com (172.66.0.243:443, checked with `ss -tnp`). Synthetic data only; no secrets or tokens included.

## Results
| Case | Result | Evidence / notes |
|---|---|---|
| REG-01 clean stack, migrations, runtime role | PASS | Flyway: 16 rows, 15 versions, all success; `dokene_runtime` rolsuper=f, bypassrls=f; Keycloak healthy ~25 s after `docker compose up -d --build` (#64 not re-run with `--wait`) |
| REG-01 two tenants / roles (#66) | PASS | QA Café Norte (OWNER/OPERATOR/VIEWER) and QA Café Sur (OWNER) |
| REG-01 `seed-local-qa.sh --verify` | FAIL (intermittent) | #125 (≈1 in 9 runs) |
| REG-02 login/logout/Back after logout | PASS | after logout `/api/session` → 401, no data on screen; local logout leaves the Keycloak SSO session, so the next login is silent (documented in ADR 0007, provider logout is opt-in) |
| REG-02 stale callback (#68) | PASS | 302 → `http://localhost:5173/?error=login_failed`; UI shows a recoverable error alert |
| REG-02 expired-session mutation (#70) | PASS | API 401 (with and without CSRF); UI shows "session expired" warning and keeps the form |
| REG-02 two tabs | PASS | logout in tab 2, write in tab 1 → session-expired warning (it says "due to inactivity", slightly inaccurate; cosmetic) |
| REG-02 refresh on deep link | NOT APPLICABLE | the SPA has no URL routing (known limitation recorded in #57); refresh returns to Seguimientos |
| REG-03 customers/phones | PASS | UI: invalid CL phone gives a field-level actionable error (#72), duplicate shows a conflict and keeps the form; API: normalization, 409 for same-tenant duplicate including an archived record, same phone allowed in another tenant, If-Match 409 on stale update; OPERATOR cannot archive (403, `CUSTOMER_DELETE` is OWNER/ADMIN only); archive by OWNER 204. Gap: UI region list has no Costa Rica (CL, AR, CO, PE, MX, ES, US) while the API accepts any supported region. Defect: #127 (API name validation message) |
| REG-04 consent | PASS | unknown→granted→revoked; DNC overrides grant; clearing DNC with revoked consent stays `CONSENT_REVOKED`; changing the phone resets consent to UNKNOWN; stale If-Match 409; VIEWER 403. UI grant verified |
| REG-05 purchases | PASS | backdated, corrected and voided purchases; "last purchase" always the latest valid; history keeps RECORDED/VOIDED; idempotent replay 200, same key + different body 409; future date 400. UI shows latest = 2026-09-05 after a backdated entry |
| REG-06 follow-ups | PARTIAL PASS | due/overdue, explicit next date, tenant IANA timezone boundary (Pago_Pago vs Kiritimati flips the tenant date), completion + replay (201 then 200, key-dominant per ADR 0013), stale If-Match 409, snooze/dismiss only for DUE/OVERDUE (409 otherwise), invalid tz/cadence 400; UI snooze executed. Not executed: dismissal success path and a second recurrence cycle (needs clock control). Observation: OPERATOR can change the tenant-wide policy (it has `FOLLOWUP_WRITE`); not documented as owner-only, raised as a question, not a defect |
| REG-07 integrated journey, live OpenAI | PASS with limitation | UI: create customer → grant consent → 2 purchases → queue (OVERDUE) → real recommendation + draft → edit → manual disposition → logout/login; persisted state consistent across list/detail/queue. Business state (customer/contact-policy/follow-up policy/queue versions) identical before and after AI calls; audit gained only 2 `AI_INVOCATION_OUTCOME` rows. Limitation: "Copy draft" success path is BLOCKED in the in-app browser (clipboard-write permission denied); the app shows a clear failure message. Defect: #126 (English rationale) |
| REG-08 AI dependency independence | PASS | enabled (REG-07), disabled (`AI_UNAVAILABLE`, clear message, manual actions usable) and unreachable provider (fault injection with base URL → closed port; **not live evidence**; "assistant unavailable" + Retry, manual snooze worked) |
| REG-09 visual fixes | PASS | 320 px customer search (no horizontal overflow, controls 44 px high, #83); 768 px shell (#82); 1024 px workbench with detail view (PR #111): no horizontal overflow |
| REG-10 new dependencies | PASS | libphonenumber 9.0.40, openai-java 4.73.0 (okhttp 4.12.0), libphonenumber-js 1.13.14; frontend `npm run build` OK; phone normalization and live OpenAI round trip exercised. Automated suites were not re-run here |

## Live OpenAI usage
2 calls (1 recommendation, 1 draft) in this suite; token counts are not exposed by the app; estimated cost well under US$0.01. Fault-injection runs made no provider calls.

## Defects
- #125 — seed script intermittently fails (Low, tooling, pre-existing)
- #126 — recommendation rationale in English in the Spanish UI (Low–Medium, origin unknown)
- #127 — API name validation message not actionable (Low, pre-existing)

## Not covered / limitations
Dismissal success path and second recurrence cycle (REG-06), clipboard copy success (REG-07), Safari/Firefox and real devices (only the in-app Chrome, emulated widths), keyboard/screen-reader accessibility (suite #122), OPERATOR archive control visibility in the UI.

Verdict for this suite: **not a clean PASS** until #125, #126 and #127 are fixed (or explicitly accepted) and retested.

## Evidence files

Evidence lives in [`issue-119-evidence/`](issue-119-evidence/). All of it is sanitized (synthetic data only; no cookies, CSRF/OIDC tokens, passwords or API keys).

| File | Purpose |
|---|---|
| `ai-side-effects-business-state-before.txt` / `-after.txt` | Customer version, contact-policy version/consent, follow-up policy and queue snapshot taken immediately before and after the live recommendation and draft calls; the diff is empty (REG-07, REG-08) |
| `audit-event-counts-before-ai.txt` / `-after.txt` | Audit event counts; the only difference is `AI_INVOCATION_OUTCOME` x2 |
| `defect-125-...md`, `defect-126-...md`, `defect-127-...md` | Text of the defect Issues filed from this run (#125, #126, #127) |

No screenshots were captured in this run; UI results were read from the DOM (accessibility tree and text) in the in-app browser, which also denies clipboard writes.

## How to reproduce the environment

1. `cp .env.example .env` and fill local-only values (set `DOKENE_AI_PROVIDER=openai`, `DOKENE_AI_OPENAI_API_KEY`, `DOKENE_AI_OPENAI_MODEL=gpt-6-luna` for live runs).
2. `docker compose up -d --build`
3. Backend with JDK 26: `cd backend && ./gradlew bootRun` (export the `.env` variables first).
4. Frontend with Node 24: `cd frontend && npm ci && npm run dev`
5. `./scripts/seed-local-qa.sh` (a second workspace was provisioned through `POST /api/tenants` as `testuser`).

## Retest of the defects found in this run

Retested on `main` @ `01bcbea` (includes the fixes for #125, #126 and #127) with the containerized stack started by `./scripts/dev-env.sh up --seed`. Only the affected cases and their adjacent workflows were re-run; the rest of this suite is **not** re-executed on the new SHA, so the evidence above remains tied to `2e0a254`.

| Defect | Fix | Retest | Result |
|---|---|---|---|
| #125 seed script intermittently fails | #129 | `seed-local-qa.sh --verify` x25 | PASS (25/25; was about 1 in 9) |
| #126 rationale in English | #150 | live OpenAI (`gpt-6-luna`): UI recommendation + draft, 2 more API recommendations | PASS (3/3 Spanish rationales; no change to customer/contact-policy versions or queue) |
| #127 non-actionable display-name error | #152 | API: blank, missing, 300, 160/161-character names, PUT without If-Match, phone error regression | PASS (Spanish message with `field: displayName` / `version`; 160 accepted, 161 rejected) |

Follow-up from REG-03: Costa Rica was missing in the UI region selectors; tracked in #153 and implemented in PR #154.
