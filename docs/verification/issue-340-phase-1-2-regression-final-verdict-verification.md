# Issue #340: Phase 3 manual QA, Phase 1/2 regression recheck and consolidated release findings

Last suite of the Phase 3 manual QA epic [#332](https://github.com/stevdrey/dokene/issues/332). Cases P3-MQA-REG-01
to REG-06.

**Suite verdict: PASS (scoped)** for candidate `753cdff45252e4f64e4eec65df3e23717c730cef` (`main`, 2026-11-19), on the
local stack. 6 of 6 cases PASS, 0 FAIL, 0 BLOCKED, no new defect. The Phase 1/2 journeys (login, workspace switch,
customers and phones, consent and do-not-contact, purchases, due/overdue queue, snooze, completion, dismissal,
archive) work on the candidate through the real UI and API. A grounded Spanish draft from real OpenAI can be generated,
edited and copied without changing consent, cadence or messages. With AI unavailable, disabled or after the session
ends, the manual workflow keeps working and the errors are clear. The high-volume queue, long histories and workspace
switching paginate correctly in Chromium and Firefox. Every older defect named in the issue is still fixed.

**Recommended campaign verdict for #332 (REG-06): PASS (scoped) with known limitations** for `753cdff4`, limited to the
scope and environments in the [consolidation](#reg-06-consolidated-campaign-findings) below. The decision belongs to
the maintainer.

## Environment

| Item | Value |
| --- | --- |
| Candidate | `753cdff4`. Outside `docs/` it is identical to `91915e2f`, the tree the running images were built from (`git diff --stat 91915e2f 753cdff4 -- . ':!docs'` is empty) |
| Stack | The #333 compose project `dokene-qa333` (disposable volumes), worktree moved to the candidate; PostgreSQL 17 at Flyway V40, Keycloak 26.8.2, backend, frontend, OIDC bridge |
| Browsers | Claude desktop built-in browser, Chrome 156.0.8078.75, driven by hand (person-driven steps). Headless Chromium 156.0.8078.4 and Firefox 160.0 (WebDriver BiDi) through Playwright `playwright-core` 1.64.1 for the clipboard, volume and layout checks. Locale `es`, Linux |
| AI provider | `fake` by default. **Real OpenAI** (`gpt-6-luna`) only in REG-02, authorized by the user for at most 6 calls: **4 calls** were made (2 `NEXT_BEST_ACTION`, 2 `MESSAGE_DRAFT`, all `GENERATED`). REG-03 used an OpenAI adapter pointed at a closed loopback port (no external call) and an empty provider (disabled). Reset to `fake` and verified |
| WhatsApp provider | Loopback Graph API stub with synthetic canary tokens. No real Meta. **Provider send requests during the suite: 0** (stub count 29 before and after) |
| Switches | Global outbound off (environment) and tenant outbound off for the whole suite; no window opened |
| Fixtures | Tenant A *QA Café Norte*: OWNER `testuser`, ADMIN `testadmin`, OPERATOR `testoperator`, VIEWER `testviewer`. Tenant B *QA Café Sur*: `testuser` ADMIN, `testoperator2` OPERATOR. `testnone` without membership. Keycloak had again dropped the extra users after its last restart; they were recreated and re-invited through the real invitation flow. About 260 disposable `QA340 …` customers (CL test-range phones `+5698340xxxx`), all archived at the end |

Evidence kinds are kept apart. *Browser* is a person-driven action in the real UI (in-app pane), read back as page
text, DOM queries and the network panel. *Real browser harness* is a Playwright run in headless Chromium/Firefox that
clicks the real UI. *API/DB* is an authenticated HTTP call through the real BFF session (cookie, CSRF token, tenant
header) or a read-only SQL check. *Real OpenAI* marks the four live calls. Nothing here comes from unit or integration
test fixtures.

## Results

| Case | Result | Browser | API / DB | Notes |
| --- | --- | --- | --- | --- |
| REG-01 Phase 1/2 journeys on the pinned SHA | **PASS** | OIDC login through Keycloak; *Nuevo cliente* with an invalid phone shows the field message for Chile (#72), then creates the customer; consent granted (*Contacto habilitado*); purchase recorded; the customer appears *Vencido hace 12 d* from the 30-day cadence; search filters the loaded queue; *Posponer* (3 days), *Registrar seguimiento* and *Descartar* each remove the card and update the counters; *Archivar* turns the profile read-only and *No elegible (Cliente archivado)*; switching to *QA Café Sur* shows only its customers and closes the Norte profile; *Archivados* lists the archived profile. 13 observations | 75 checks (`reg01-api340.sh`): 401 without session, workspace roles and 403 across workspaces; phone, name and region validation; optimistic versions; search by name and by national phone; same phone allowed in B; consent/DNC precedence and history; purchases with idempotent replay, key reuse 409, future date 400, correction, void and last-purchase rules; OVERDUE from last purchase, DUE from explicit date, snooze rules, completion and dismissal with replay and stale-version checks; VIEWER and OPERATOR limits; archive (403 OPERATOR, 400 without `If-Match`, idempotent, readable, excluded, not eligible, out of the queue, no new purchase, phone stays reserved, no disposition, AI recommendation refused). Browser writes read back (5 checks). #421 provisioning: one workspace provisioned under the allow-all policy, idempotent replay, OWNER of only that workspace (4 checks) | The API has no "clear snooze" operation (ADR 0013); see setup note 1 |
| REG-02 Real OpenAI draft: generate, edit, copy | **PASS** | In-app pane: *Obtener recomendación* → *Seguimiento por recompra*, Spanish rationale grounded in the coffee-and-filters purchase, 94 %; *Generar borrador* → an editable Spanish draft that names only the purchased products, with no price, link or discount, *Edición local: no se guarda ni se envía*. The edit is kept. *Copiar borrador* says *No se pudo copiar el borrador* there because the pane denies clipboard writes. In headless Chromium with clipboard permission the same flow says *Borrador copiado* and the clipboard equals the edited draft | Consent, DNC and contact-policy version unchanged; follow-up cadence, explicit date, snooze and version unchanged; customer still `DUE`; no message, approval or send attempt; no stub request | 4 live calls, inside the authorized 6 |
| REG-03 AI unavailable/disabled and session expiry | **PASS** | Session expiry: with the snooze dialog open, the backend restart ended the BFF session; *Confirmar fecha* got 401 and the page said *Tu sesión ya no está activa…*, not a permission error (#70); nothing stored. Unavailable: *El asistente IA no está disponible en este momento… También puedes reintentar* with *Reintentar*; a completion saved; the 17-row message history refreshed. Disabled: *…no está habilitado para este espacio de trabajo…* with no retry; a snooze saved | Per mode: recommendation and draft → 200 `AI_UNAVAILABLE` (`UNAVAILABLE`/retryable, or `NOT_AVAILABLE`/not retryable) with the deterministic evaluation and no raw provider error; nothing written; snooze, completion, history and queue work; an ended session's cookie and CSRF token get **401** on snooze, completion and recommendation (24 checks). Browser writes read back (5 checks) | Session end was produced by logout and a backend restart; the 30-minute idle and 8-hour absolute timeouts were not waited out |
| REG-04 High volume, long pages, workspace switching | **PASS** | Chromium and Firefox: the queue loads 50, *Cargar más seguimientos* ×4 → 235 cards, 230/230 volume customers once, then the control disappears; switching to Sur with the full list shows only Sur's card, and back shows a fresh first page; a customer with 122 purchases loads 50, then *Cargar compras anteriores* ×2 → 122 | Queue pages of 100 and 37: every volume customer once, no duplicate; due date never decreases across 7 pages; `OVERDUE`/`DUE` filters pure; limit 101 → 400; forged cursor → 400; an A cursor replayed in B returns only B data; B's whole queue holds no A customer; customer search (5 pages of 50), archived list (209) and purchase history (122) match the database exactly (13 checks) | Fixture limits from #140 are separated in the next section |
| REG-05 Older defects | **PASS** | #72 field message; #70 session-ended message; #68 stale callback lands on the app with *No se pudo completar el inicio de sesión…* when signed out and keeps the session when signed in; #82 and #83: no page overflow at 768 px and 320 px on *Clientes* and *Seguimientos* in Chromium and Firefox | #66, #68, #72, #265, #266, #293 (20 checks), #70 (REG-03), #64 (clean Keycloak start) | Table below |
| REG-06 Consolidation and verdict | **PASS** | – | #310 recheck on the candidate (2 checks) | [Consolidation](#reg-06-consolidated-campaign-findings) |

Totals in `issue-340-evidence/outputs.txt`: 188 PASS result lines, 0 FAIL, 19 NOTE in the final runs, 25 person-driven
browser observations (24 PASS, 1 NOTE), and the real-browser harness outputs. Superseded harness runs are kept at the
end as `HARNESS-ERROR` and are not counted (setup notes 1 to 4). Some read-only verification scripts were run twice on
purpose; their repeated lines are included.

### REG-04: product behaviour versus fixture limits (#140)

The #140 report marked some checks as harness limits because the migrated database held more due customers than the
scripts expected (for example a recurrence check reading only the first 100 queue items, or a UI script asserting a
clean-database total of 81 cards). This suite avoids that class of error: every check counts against the database for
the same filter (230 volume customers, 209 archived, 122 purchases) or walks every page until the cursor ends, and no
check assumes a clean-database total. No product defect hides behind a fixture limit here. The UI counters say
*(en lista)* and count only the loaded cards, which is the documented behaviour, not a pagination fault.

### REG-05: older defects on the candidate

| Defect | Recheck on `753cdff4` | Result |
| --- | --- | --- |
| [#64](https://github.com/stevdrey/dokene/issues/64) Keycloak exceeds the Compose wait | A disposable compose project with only Keycloak: `docker compose up --wait --no-build keycloak` → exit 0, healthy in **15 s** (start period 45 s plus 15 retries of 5 s); discovery endpoint 200; project removed | Holds. A cold image build was not timed (images prebuilt, `--optimized`) |
| [#66](https://github.com/stevdrey/dokene/issues/66) No second identity / membership mechanism | Extra users created through the Keycloak admin API and memberships granted through the real invite/accept routes; `testadmin` ADMIN in A, `testoperator2` OPERATOR in B only | Holds |
| [#68](https://github.com/stevdrey/dokene/issues/68) Stale callback shows Spring's login page | `/login/oauth2/code/dokene` with a stale code/state, on :8080 and :5173 → 302 to `/?error=login_failed`; in the browser a Spanish recoverable message; a live session survives | Holds |
| [#70](https://github.com/stevdrey/dokene/issues/70) Expired-session mutation gets 403 | 401 on three mutations with an ended session's cookie and CSRF token (both AI modes); UI shows the session message | Holds |
| [#72](https://github.com/stevdrey/dokene/issues/72) Generic HTTP 400 on bad phone | 400 naming `phones[0].number` (CL and CR); UI shows the region-specific message | Holds |
| [#82](https://github.com/stevdrey/dokene/issues/82) / [#83](https://github.com/stevdrey/dokene/issues/83) Overflow at 768 / 320 px | `scrollWidth` equals the viewport, no element past the edge, no clipped control (Chromium and Firefox) | Holds |
| [#265](https://github.com/stevdrey/dokene/issues/265) Two denial rows for one any-of denial | VIEWER cancel of a pending message → 403, exactly one row naming `MESSAGE_DRAFT`, nothing else written | Holds |
| [#266](https://github.com/stevdrey/dokene/issues/266) `/webhooks` siblings set `JSESSIONID` | 7 sibling paths → 403, no `Set-Cookie`, no challenge echo; the real callback echoes the challenge without a cookie | Holds |
| [#293](https://github.com/stevdrey/dokene/issues/293) Default 500 body on Phase 3 routes | With `SELECT` revoked from the runtime role on this disposable database, `GET /api/templates` and `GET /api/integrations/whatsapp` → 500 `application/problem+json` with exactly `type,title,status,code`, `INTERNAL_ERROR`, no path, timestamp or exception text; grants restored and both routes 200 again | Holds |

Historical closure was not assumed: each line above is a fresh observation on the candidate.

## REG-06: consolidated campaign findings

### Child suites

| Suite | Tested SHA (product tree) | Result | Defects | Provider evidence |
| --- | --- | --- | --- | --- |
| [#333](https://github.com/stevdrey/dokene/issues/333) Environment and WhatsApp readiness | `3807963` | PASS (scoped) 6/6 | none | Graph stub; fake AI |
| [#334](https://github.com/stevdrey/dokene/issues/334) Draft review and revision-bound approval | `1ad11c8` (= `3807963`) | PASS (scoped) 7/7 | none | Real OpenAI (9 calls); stub (1 send) |
| [#335](https://github.com/stevdrey/dokene/issues/335) Guarded sends and idempotency | `571fff0` (= `3807963`) | PASS (scoped) 7/7 | none | Stub (12 calls, two windows) |
| [#336](https://github.com/stevdrey/dokene/issues/336) History, callbacks, reconciliation | `9504dca` (= `3807963`) | PASS (scoped) 6/6 | none | Stub (9 sends); signed synthetic callbacks |
| [#337](https://github.com/stevdrey/dokene/issues/337) Security boundaries | `21dd085` (= `3807963`) | PASS (scoped) 7/7 | none (L1 #310 then open) | Stub, no send |
| [#338](https://github.com/stevdrey/dokene/issues/338) UI/UX, accessibility, recovery | `42eec81`; fixes retested on `91dc65a`; certified on `91915e2f` | FAIL → PASS (scoped) after fixes, 136 PASS / 0 FAIL on `main`; screen reader BLOCKED | [#415](https://github.com/stevdrey/dokene/issues/415)–[#418](https://github.com/stevdrey/dokene/issues/418), fixed in [#420](https://github.com/stevdrey/dokene/pull/420) | Stub (7 sends, one window) |
| [#339](https://github.com/stevdrey/dokene/issues/339) Opt-in real Meta journey | `c53cea24` (= `91915e2f`) | PASS (scoped) with one deviation (LIVE-06) | none | **Real Meta** Graph v23.0, 1 authorized send, real signed `delivered` and `read` callbacks |
| #340 Phase 1/2 regression (this suite) | `753cdff4` (= `91915e2f`) | PASS (scoped) 6/6 | none | Real OpenAI (4 calls); stub, no send |

### Validity of earlier evidence on the candidate

Between the first suite and the candidate, three product changes landed (`git log 3807963..753cdff4 -- . ':!docs'`):

| Change | Area | Covered on the final tree by |
| --- | --- | --- |
| [#420](https://github.com/stevdrey/dokene/pull/420) (#415–#418 fixes) | Frontend: message labels, integration status, shell and shared components | #338 rerun on `91915e2f` (136 PASS in Chromium and Firefox); #339 and #340 ran on the same tree |
| [#413](https://github.com/stevdrey/dokene/pull/413) (#310, V40) | Authorization-denial audit bound | #340 REG-05 #265 (one row per denial) and REG-06 #310 (51 denials → 10 rows, same 403). The #337 "exactly one denial row" checks stay inside the 10-row budget, so they are not affected |
| [#421](https://github.com/stevdrey/dokene/pull/421) (#414) | Workspace provisioning policy at startup | #340 REG-01 provisioning checks under the dev allow-all policy; the allowlist mode and the startup refusals were not exercised on the stack |

Suites #333 to #337 ran on the `3807963` tree. Their Phase 3 routes (integration, templates, approval, sends, webhooks,
history) are untouched by these three changes, so their evidence carries over for those routes.

### Browser, role and provider matrix

| Dimension | Covered | Not covered |
| --- | --- | --- |
| Browsers | Chrome 156 (desktop built-in pane, person-driven) in #333–#337 and #340; headless Chromium 156 and Firefox 160 in #338 and #340; foreign-origin probe pages in #337 | Safari/WebKit, real mobile devices, Windows/macOS browsers |
| Roles | OWNER, ADMIN, OPERATOR, VIEWER and a non-member in both workspaces (#333, #334, #337, #340) | – |
| AI | Real OpenAI (#334: 9 calls, #340: 4 calls), deterministic fake, injected unavailable and disabled modes | Genuine provider 429/5xx and refusals are not forceable (injected or simulated) |
| WhatsApp | Loopback Graph stub with fault modes (#333–#338), signed synthetic callbacks (#336), real Meta with one send and real callbacks (#339) | Live `failed` callback (covered offline in #336); more than one template and recipient on Meta |
| Deployment | Single local backend instance, one PostgreSQL | Multi-instance behaviour (per-instance rate limits and denial budgets), production hosting |

### Security hardening (#305)

| Finding | State on `753cdff4` | Effect on this campaign |
| --- | --- | --- |
| [#310](https://github.com/stevdrey/dokene/issues/310) L1 denial-audit amplification | Fixed by #413 and rechecked here | Resolved; the #337 note "known open" is superseded |
| [#312](https://github.com/stevdrey/dokene/issues/312) L3 workspace self-provisioning | Open; [#414](https://github.com/stevdrey/dokene/issues/414) (a stated policy at startup) is merged. The dev stack runs `allow-all-identities=true` | Not a Phase 1/2/3 regression; deployments must choose the allowlist |
| [#318](https://github.com/stevdrey/dokene/issues/318) L7 OAuth redirect origin on direct backend access | Open | Not exercised by the campaign's journeys; tracked in #305 |
| The other 16 linked findings (M1–M5, L2, L4–L6, L8–L14) and #325–#327, #329 | Closed | None |
| #149 accepted constraints (metrics half of P3-SEC-07, P3-SEC-11) | Unchanged, by design | Deployment limitation |

### Defects, fixes and retests

| Defect | Severity | Found by | Fix | Independent retest |
| --- | --- | --- | --- | --- |
| [#415](https://github.com/stevdrey/dokene/issues/415) queue search focus indicator, focus after submit, skip link | Medium | #338 UX-03 | #420 | PASS on `91dc65a`, certified on `91915e2f` |
| [#416](https://github.com/stevdrey/dokene/issues/416) button labels break inside a word at 320 px | Low | #338 | #420 | same |
| [#417](https://github.com/stevdrey/dokene/issues/417) multiline draft bodies refused with a generic sentence | Medium | #338 UX-06 | #420 | same |
| [#418](https://github.com/stevdrey/dokene/issues/418) credential-reference error reads as an outage; outdated AI disclaimer | Medium | #338 UX-05 | #420 | same |

No other reproducible defect was found by suites #333–#340, and no defect found by the campaign is open. None of the
four was a safety, tenant or security defect.

### Known limitations of the campaign verdict

1. **Real screen reader (UX-03 part) BLOCKED** (#338): the accessibility tree and keyboard paths passed in Chromium and
   Firefox, but no NVDA/Orca/VoiceOver session was run.
2. **#339 LIVE-06 deviation:** as recorded by #339, the Meta app's Callback URL was left pointing at a stopped tunnel and the test recipient's
   cadence was cleared by the send helper. Before any further live run the operator must reset the Callback URL (and
   pass Meta's verification `GET`) and restore the cadence. This is an operator task, not a product defect.
3. Real Meta coverage is one Spanish template, one recipient and one send; a live `failed` callback was not produced.
4. An unknown send outcome without a provider id can only be resolved through the T12 API (no UI), by design.
5. Idle (30 min) and absolute (8 h) session timeouts, the 15-minute denial-audit window and retention windows were not
   waited out; session end was produced by logout, IdP back-channel logout and backend restart.
6. Single backend instance; no Safari, no real mobile devices.
7. #312 and #318 remain open hardening items (#305), and the dev stack's provisioning policy is allow-all.
8. Evidence is for the exact candidate `753cdff4` (product tree `91915e2f`) on a local stack. Later commits and other
   deployments are not certified by it.

### Mapping to the #332 exit criteria

| Exit criterion | Status |
| --- | --- |
| Every suite has per-case results, environment and SHA; BLOCKED/FAIL items disclosed | Met (table above; screen reader BLOCKED; #338 FAIL fixed and retested; #339 deviation disclosed) |
| Draft → explicit approval → safe refusal or authorized send → history, acceptance never shown as delivery | Met (#334, #335, #336, #339) |
| Role, tenant, CSRF, consent, revision, kill-switch and duplicate-send boundaries with zero unauthorized provider calls | Met (#335, #337; #340 kept the stub count at 29) |
| Accessibility/responsive/recovery and Phase 1/2 workbench regressions checked | Met (#338, #340), screen reader BLOCKED |
| Real Meta leg PASS only with consent, credentials and live callbacks | Met (#339), with the LIVE-06 operator follow-up |
| Every defect linked and retested on the fix revision | Met (#415–#418) |
| Consolidated table, defects, limitations, hardening dependencies and scoped verdict | This section |

## Observations

| Id | Observation | Classification |
| --- | --- | --- |
| O1 | The in-app browser pane denies clipboard writes, so *Copiar borrador* reports *No se pudo copiar el borrador* there; the edited text is kept and the message is accurate. In a browser that grants clipboard access the copy works | Environment limit, not a defect |
| O2 | Switching back to a workspace reopens the last customer profile of that workspace (per-workspace state). No data of the other workspace is shown | Expected |
| O3 | A cursor from workspace A replayed with workspace B's header returns B's own first page (the cursor is a position, not a capability); no A data | Expected; no leak |
| O4 | A non-member's denial on a Phase 1 route (`/api/customers`) answers Spring's default 403 body with a timestamp, not a problem body. The #293 problem contract covers Phase 3 routes only | Unchanged behaviour, outside the #293 contract |
| O5 | The API offers no way to clear a snooze; an early completion is allowed while snoozed and clears it (ADR 0013) | By design |

## Setup notes

1. **Harness errors, REG-01 (superseded).** The first run expected a "clear snooze" with `{"until":null}`; the API
   requires a date (O5), so the check now expects 400 and the following completion runs on the snoozed customer. The
   second run's workspace B search returned two `QA340 Ana Sur` customers because the first run had created one; the
   check now accepts any number of B results and requires that all belong to B and none to A. The third run's quoting
   of those ids was wrong. The final, fourth run passed 75/75.
2. **Harness error, REG-06 #310 (superseded).** The first #310 run looked for denial rows by actor and compared whole
   bodies; a non-member's denial row has no actor or tenant, and the 403 body carries a timestamp. The rerun counts the
   key's rows in the window and compares bodies without the timestamp.
3. **Fixture collision, REG-04.** REG-05 ran while the volume fixture was still recording the 120 purchases, and both
   used the same `testuser` cookie-jar file; REG-05's fresh login ended the fixture's session, so 5 purchases were not
   recorded. They were recorded afterwards with the same keys (all 201); one description was recorded twice, which the
   harness output explains. The first Chromium harness run read the purchase count before the list loaded, and the
   first REG-04 API run lacked the ordering check; both were rerun.
4. **Side effect.** The #421 provisioning check made `testnone` the OWNER of the disposable workspace *QA340 Taller
   Provisionado*. Later suites that need an identity with no membership at all must create a new one.
5. **Script revisions after the run.** The two AI-mode NOTE lines of REG-03 were recorded by a revision of
   `reg03-api340.sh` that also printed the provider base URL; that field was removed afterwards because the
   repository's provider-test-double guard rejects any evidence script that names that setting. `reg03-api340.sh` and
   `reg05-api340.sh` now also load `scripts/lib/curl-protected.sh` themselves (the `bff-login-test.sh` rule), and
   `reg05-api340.sh` reads the synthetic webhook verify token from the backend environment instead of spelling it out
   (as `sec06-337.sh` does). No check or expectation changed.
6. **Keycloak users.** Keycloak keeps its users in memory; after a restart the extra users must be recreated
   (`fixtures337.sh`, run with `OUTD=~/qa340/out`).

## Reproducing

From the #333 stack (see its report), with the extra users present, from the repository root:

1. `bash docs/verification/issue-340-evidence/scripts/env340.sh`
2. REG-01: `reg01-api340.sh`, then the browser steps in the table, with `reg01-browser-prep340.sh` before and
   `reg01-browser-verify340.sh` after; `reg01-provisioning340.sh`.
3. REG-02 (needs authorization for real OpenAI: `DOKENE_AI_PROVIDER=openai` in the stack's `.env`, then
   `stack333.sh restart`): `reg02-prep340.sh`, browser steps, `reg02-verify340.sh`,
   `QA_PW=… PW_HOME=<dir with playwright-core 1.64.1> node reg02-copy340.mjs`, `reg02-verify340.sh`.
4. REG-03: `reg03-prep340.sh`; browser session-expiry step; switch the provider to a closed loopback base URL and run
   `reg03-api340.sh unavailable`; empty provider and `reg03-api340.sh disabled`; `reg03-browser-verify340.sh disabled`.
   Restore `DOKENE_AI_PROVIDER=fake`.
5. REG-04: `reg04-fixtures340.sh`, `reg04-api340.sh`, `node reg04-ui340.mjs chromium` and `… firefox`.
6. REG-05: `reg05-api340.sh` (revokes and restores two grants: never on a shared database); #64:
   `COMPOSE_PROJECT_NAME=dokene-qa340kc KEYCLOAK_PORT=18081 docker compose up --wait --no-build keycloak`, then `down -v`.
7. REG-06: `reg06-sec310-340.sh`; close-out `final340.sh`; bundle `python3 build-outputs340.py`.
