# Issue #333: Phase 3 manual QA, real-stack baseline and WhatsApp readiness

Suite of the Phase 3 manual QA epic [#332](https://github.com/stevdrey/dokene/issues/332). Cases P3-MQA-ENV-01 to ENV-06.

**Verdict: PASS (scoped)** for candidate `3807963859888ff1ad1c6b258135f50879fd2452` (`main`, 2026-11-16), on the local
stack in controlled provider mode. 6 of 6 cases PASS, 0 FAIL, 0 BLOCKED, no new defects. All four roles were available.
This is not real Meta evidence: that is suite [#339](https://github.com/stevdrey/dokene/issues/339).

## Environment

| Item | Value |
| --- | --- |
| Candidate | `3807963859888ff1ad1c6b258135f50879fd2452` (not the historical certified revision `80c7ab7`) |
| Stack | Detached worktree at the candidate, compose project `dokene-qa333`, fresh PostgreSQL volume, `./scripts/dev-env.sh up --seed` with images built from the candidate |
| Versions | Docker 29.9.3, Compose 5.7.1, Keycloak 26.8.2, PostgreSQL 17.12, Flyway V1 to V39 all `success` |
| Browser | Claude desktop built-in browser, Chrome 156.0.8078.75 on Linux, driven by hand through the real UI |
| AI provider | `fake` (not exercised in this suite) |
| WhatsApp provider | Loopback Graph API stub (`docs/verification/issue-149-evidence/scripts/GraphStub.java`) in the backend's network namespace, local secret source with synthetic canary tokens. No real Meta call is possible in this mode |
| Switches | Global and tenant outbound **off** for the whole run, except one user-approved ENV-04 window (below) |
| Fixtures | Tenants *QA Café Norte* (A) and *QA Café Sur* (B). A: OWNER `testuser`, ADMIN `testadmin`, OPERATOR `testoperator`, VIEWER `testviewer`. B: OWNER `testowner2`, OPERATOR `testoperator2`, and `testuser` as ADMIN for workspace switching. Non-member `testnone`. Customers: A has one DUE, two INELIGIBLE (no consent, consent revoked) and one NOT_YET_DUE; B has one DUE. Invented names and invented `+5698…` numbers (Chile has no reserved fictitious range; the same style as the #149 fixtures; nothing was sent to them) |

Evidence kinds are kept apart. *Manual browser* means a person-driven action in the real UI. *API* means an
authenticated HTTP or DB check through the real BFF session (OIDC login, session cookie, CSRF). *Stub* means
controlled-provider evidence. No result here comes from unit or integration test fixtures, and none comes from
real Meta.

## Results

| Case | Result | Manual browser | API / DB | Notes |
| --- | --- | --- | --- | --- |
| ENV-01 Clean stack, migrations, login | **PASS** | OWNER OIDC login lands on the workbench with the seeded follow-up; the only console error is the expected pre-login `GET /api/session` 401 | All five services healthy, `migrate` exited 0, Flyway V39; backend log has 0 ERROR lines and only the two expected "webhook verification refusing" warnings while the refs are unset | Setup notes below |
| ENV-02 Two tenants, four roles, due and ineligible data | **PASS** | Both workspaces listed for the dual-tenant user | Invite → INVITED (no access) → accept → VIEWER read and no write → role change to OPERATOR takes effect on the same session → revoke (403) → reactivate (INVITED, still 403); roles resolve per `X-Tenant-Id`; lists are tenant-scoped; no tenant selected → 403 | Eligibility: DUE `DUE_TODAY`, INELIGIBLE `NO_ELIGIBLE_CONTACT` (×2), NOT_YET_DUE `CADENCE_NOT_DUE`. Seed coverage gap: `seed-local-qa.sh` creates OWNER/OPERATOR/VIEWER only; ADMIN and tenant B come from `fixtures333.sh` |
| ENV-03 Readiness inspection, secrets, role gating | **PASS** | OWNER and ADMIN see *Editar* and *Verificar propiedad*. OPERATOR and VIEWER see 0 buttons, 0 inputs and the "Solo lectura" note. Credential shown as the reference name only | Unconfigured (404 `NOT_FOUND`, all roles alike) and configured (200): OPERATOR and VIEWER get 403 on PUT, tenant switch, ownership proof and credential rotation, and nothing changes (same ETag). VIEWER template write 403. No route for the global switch. ADMIN rotation clears the proof and re-proof works | Canary search: 0 hits in the DOM, in integration, template and session responses, in backend and migrate logs, and in a full data dump (the DB holds the reference name only). No JS-visible cookies, no `localStorage`; `sessionStorage` holds only the active tenant id |
| ENV-04 Configure, prove ownership, disabled states | **PASS** | Invalid id blocked client-side with focus on the field. Integration created through the UI, readiness *No lista para enviar*. A number the provider does not list → safe error, stays unproven. Owned number → *Propiedad comprobada*. ADMIN disable → confirmation → *Deshabilitada*, proof cleared, then re-enable and re-prove | Number change, disable and credential rotation each clear the proof. A tenant B integration naming A's credential ref cannot prove. B can *store* A's number unverified (by design, ADR 0024 issue 210) but cannot *prove* it, and A keeps its proof. Global off blocks a proven, tenant-on send with `OUTBOUND_DISABLED_GLOBALLY` | **Unproven or disabled integration cannot send**: isolated in the approved window below, `INTEGRATION_DISABLED` both times, 0 stub sends |
| ENV-05 Template mapping lifecycle | **PASS** | List reads *1 de 5 plantillas listas para enviar*, *Lista* or *Sin asignar* per intent; the list is read-only in the UI | New mapping `UNVERIFIED`/not sendable. Refresh: exact name, language and schema → `APPROVED`/sendable. Provider other name, other language or absent → `NOT_FOUND`. `REJECTED`, `PAUSED`, `DELETED` → stored as reported and not sendable. One placeholder against two mapped → `SCHEMA_MISMATCH`. Provider 5xx → 503 `PROVIDER_UNAVAILABLE`, nothing invented. Disable → `UNVERIFIED`/not sendable; re-enable → refresh → `APPROVED`. Rename drops evidence. Stale `If-Match` → 409 `STALE_VERSION`. Unknown intent → 404. Tenant B unaffected | Mapping writes are API-only on this candidate (no editor in the UI). 12 template lookups, all with tenant A's credential |
| ENV-06 Switch, reload, expire while loading | **PASS** | Switching Norte→Sur on *Configuración*: the first render is already Sur *sin configurar*, and a DOM mutation observer saw no tenant-A value. Reload keeps Sur. Session revoked (Keycloak admin logout, delivered by back-channel) and then switching to Norte: the configuration `GET` answers 401, the UI shows *Comprobando…* and then "Tu sesión ya no está activa" with a login button; no tenant-A value rendered; re-login needs fresh credentials | — | Logout residual below |

Totals in `issue-333-evidence/outputs.txt`: 117 PASS check lines, 19 NOTE lines (observations and cleanup), 0 FAIL.
Provider send calls during the whole suite: **0**.

Reading `outputs.txt`:

- `[browser]` lines were written right after the browser session, so their timestamps (18:47:34Z to 18:47:35Z)
  are the recording time, not the time of each observation.
- The line "send with unproven integration is refused" from `env04-gates333.sh` ran with the global switch off.
  Its check accepts any refusal, and the refusal came from the global gate (`OUTBOUND_DISABLED_GLOBALLY`). The
  proof gate itself is shown only by the `[proof-gate]` lines.
- After the run, two script fixes went into the committed copies:
  - `qa333.sh` now loads `scripts/lib/curl-protected.sh` relative to its own path, as invariant 35 requires.
    During the run the wrapper was already active through `bff-login.sh`, so no session value reached curl's
    arguments.
  - The final count query in `env04-gates333.sh` now names `outbound_send_attempts`. Its NOTE line had failed
    during the run and was dropped.
  - `env02-membership333.sh` now reads the `.customers` list, compares fixture names for the tenant-scope check,
    and archives the probe customer with `If-Match`. During the run both lines were first recorded wrong (the old
    key counted envelope fields; the archive without `If-Match` answered 400). They were corrected by hand on the
    live stack, giving the recorded lines "customer lists are tenant scoped (disjoint names)" and "cleanup: archive
    probe customer (with If-Match)", and the wrong lines were removed.
  - After code review the scripts were hardened without changing the recorded lines:
    - `env04-gates333.sh` and `env04-proofgate333.sh` assert the loopback stub and the expected global switch
      before any request (`assert_safe_stack`);
    - the stub send counter fails when the stub log is missing, instead of passing on two empty counts;
    - the proof-gate script restores the tenant (probe cancelled, tenant switch off, number re-proven) in an
      EXIT trap;
    - the disabled-integration and provider-5xx checks require the exact error code;
    - the global-route probe sends `enabled:false`;
    - fixtures stop on a missing tenant and do not duplicate customers on a rerun.
  - After the review on #408, the proof-gate cleanup was made fail-safe. It is installed before anything can abort.
    It restores the tenant first, then restarts the backend with the global switch OFF and verifies the effective
    global and tenant values after the restart. It ignores further interrupts and runs the restart in its own session,
    so a process-group signal cannot cut it short. It exits nonzero on a cleanup failure, and every script exits
    nonzero when a FAIL was recorded (`qa_finish`). Verified on the stub stack with:
    - a normal run;
    - a forced abort;
    - SIGTERM at five points.

    Two SIGTERM runs with earlier versions of the cleanup left the backend stopped (fail closed) and were recovered
    by hand. The last version passed every case. The details are in the "Post-review verification" section of
    `outputs.txt`. That verification covers the cleanup only; the six case verdicts are still those of the recorded
    run.

### Controlled deviation (approved by the user in the session)

ENV-04 asks to show that an unproven integration cannot send. ADR 0024 enforces the proof at the send gate,
which evaluates the global switch first. With the global switch off, the proof gate is never reached. With the
user's explicit approval, `env04-proofgate333.sh` ran with the global switch on. The script first asserts that
the Graph base URL is the loopback stub; every credential was synthetic, so no real WhatsApp message was
possible. There were **two** global-ON windows, each under one minute and about two minutes in total, and each
was followed by a restart with the global switch off and a check of the container environment (`false`):

1. **Aborted attempt** (window from about 18:39:10Z). The script submitted probe message `e6792fcd…`
   (18:39:32Z), but read the message id from the wrong response member (`id` instead of `messageId`). Its
   approval and send requests therefore went to an invalid id and answered `400 INVALID_INPUT`. The message was
   never approved and no send attempt was created. After the global switch was back off, the message was cancelled
   from `PENDING_APPROVAL` at 18:40:15Z. The 9 result lines of this attempt, including one FAIL that was only that
   harness bug, were **removed** from `results.tsv` and are not in `outputs.txt`.
2. **Valid run** (window about 18:40:20Z to 18:40:41Z, the backend restart time). Probe message `31c487a1…` was
   submitted and approved for the DUE customer, with tenant A unproven, enabled and tenant switch on: send → 409
   `INTEGRATION_DISABLED`. After proving and then disabling the integration: send → 409 `INTEGRATION_DISABLED`.
   The message was cancelled, the tenant switch set back off and the integration re-proven.

Across both windows the stub recorded 0 send calls and `outbound_send_attempts` holds 0 rows. Earlier, one probe
message (`2999c90d…`, 18:37Z) was submitted, approved and cancelled with the global switch off for
`env04-gates333.sh`. All three probe messages are `CANCELLED`.

## Defects and observations

No new defect. Searched first: #265, #266 and #293 (closed, unrelated), the #305 children #303 to #324 (none covers
these observations) and the logout and SSO history (#51, #52, #57, #307).

| Id | Observation | Classification |
| --- | --- | --- |
| O1 | The app's *Cerrar sesión* posts `/logout` without `provider=true`, so the Keycloak SSO session survives and the next *Iniciar sesión* re-enters silently as the previous user | Known residual: ADR 0007 and security audit 2026-11-02 M3 item 4 (fixed scope #307). Relevant for shared devices; not new |
| O2 | A template row shows *Lista* while the integration is disabled or unproven. Template `sendable` is template-level (ADR 0025); the panel readiness line correctly says *No lista para enviar* with the reasons | UX observation for #338. Not a contract failure: send is refused |
| O3 | The tenant outbound switch and template mapping edits have no UI control; they are API-only on this candidate. The panel shows the switch state (*Envíos pausados*) | Scope note. ENV-04 and ENV-05 allow "UI/API" |
| O4 | A proof with a credential reference the tenant cannot resolve answers 503 `PROVIDER_UNAVAILABLE` ("El proveedor no está disponible…"), which points the operator at the provider rather than at the configured reference | Low UX observation. Safe: no secret, no cross-tenant disclosure |

## Setup notes (ENV-01)

- A crash-looping stack from another worktree held host port 8080. The OIDC callback is registered on
  `localhost:8080`, so a second stack with shifted ports cannot be used for browser login (`dev-env.sh` says the
  same). That stack was stopped with the user's consent; its volumes were untouched.
- The #149 Graph stub must run as the host user when started from the backend image (uid 10001 cannot write the
  control directory). The first ownership proof attempt answered 503 for that reason. It is a QA harness
  artifact, not counted, and `stack333.sh` runs the stub with `--user`.
- Every backend restart (needed to apply the stub override) invalidates BFF sessions, as #149 documents.

## Reproducing

From a worktree of the candidate, after `./scripts/dev-env.sh up --seed`, create `$QA_HOME/.extra-users-pw` (mode 600, one
random password for the four extra synthetic users, for example `openssl rand -base64 24`), then run the scripts
from this directory, in this order:
`fixtures333.sh`, `customers333.sh`, `env02-membership333.sh`, `env03-roles333.sh unconfigured`,
`stack333.sh up`, then the browser steps for ENV-04 (UI create and prove), `env05-templates333.sh`,
`env04-gates333.sh`, `env03-roles333.sh configured`, and the browser steps for ENV-03 and ENV-06.
`env04-proofgate333.sh` needs `QA333_OUTBOUND=true stack333.sh restart` first and **must** be followed by
`stack333.sh restart` (global off). Run it only with the loopback stub, which the script asserts. The scripts
are in [`issue-333-evidence/scripts/`](issue-333-evidence/scripts/), and the recorded run is
[`issue-333-evidence/outputs.txt`](issue-333-evidence/outputs.txt).

## Limitations

- Controlled provider mode only. The stub accepts any listed phone number id for any business account, so the
  "one verified mapping per number" result shows the server rule, not Meta's own ownership check.
- The session-expiry case revokes the session just before the configuration load starts; it does not interrupt
  a request in flight.
- Backend log scans cover the containers of this run. Recreating a container drops its earlier logs, so the
  scans were run after the last restart and cover that window.
- The stack ran with `DOKENE_AI_PROVIDER=fake`; AI behaviour is out of scope for this suite.
