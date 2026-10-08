# Issue 119 verification: regression revalidation after Phase 2 AI changes

Campaign: #118. Suite: #119. Status at the time of writing: executed, awaiting defect fixes and retest (no clean PASS).

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
