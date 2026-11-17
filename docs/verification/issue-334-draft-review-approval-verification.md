# Issue #334: Phase 3 manual QA, AI draft review and revision-bound approval

Suite of the Phase 3 manual QA epic [#332](https://github.com/stevdrey/dokene/issues/332). Cases P3-MQA-FLOW-01 to FLOW-07.

**Verdict: PASS (scoped)** for candidate `1ad11c8168285cfd6448ee80aba8e557c3b6b4e6` (`main`, 2026-11-16), on the local
stack with real OpenAI and the loopback Graph stub. 7 of 7 cases PASS, 0 FAIL, 0 BLOCKED, no new defects. The browser
showed explicit approval bound to an immutable revision, no AI-originated authorization, no automatic send, and the
same history across reloads. Not real Meta evidence: that is suite [#339](https://github.com/stevdrey/dokene/issues/339).

## Environment

| Item | Value |
| --- | --- |
| Candidate | `1ad11c8168285cfd6448ee80aba8e557c3b6b4e6`. It differs from the #333 candidate `3807963` only under `docs/`; `backend/`, `frontend/`, `scripts/` and `compose.yaml` are byte-identical, so the images built from `3807963` were reused (backend `sha256:66e6c0646b08`, frontend `sha256:3353423880b4`) |
| Stack | The #333 compose project `dokene-qa333` (disposable volume, Flyway V39), worktree moved to the candidate. Docker 29.9.3, Compose 5.7.1 |
| Browser | Claude desktop built-in browser, Chromium 156.0.8078.75 on Linux, driven by hand through the real UI; two tabs for FLOW-05 |
| AI provider | **Real OpenAI**, model `gpt-6-luna`, authorised by the user for this suite: 9 calls (4 `NEXT_BEST_ACTION`, 5 `MESSAGE_DRAFT`, one of them `MODEL_REFUSED`), counted from `audit_events`. Reset to `fake` after the run |
| WhatsApp provider | Loopback Graph API stub (`docs/verification/issue-149-evidence/scripts/GraphStub.java`) with synthetic canary tokens. No real Meta call is possible in this mode |
| Switches | Global and tenant outbound **off**, except the user-approved stub-only window W1 (below) |
| Fixtures | The #333 tenants and customers. Tenant A *QA Café Norte*: OWNER `testuser`, OPERATOR `testoperator`, VIEWER `testviewer`; due customer *QA333 Ana Vencida*. Tenant B *QA Café Sur* (`testuser` is ADMIN there). Template intent `REPEAT_PURCHASE` was mapped through the API for this suite (`map-repeat334.sh`), because the live AI recommended that action and only `GENERAL_FOLLOW_UP` was mapped |

Evidence kinds are kept apart. *Browser* means a person-driven action in the real UI. *API/DB* means an authenticated
HTTP or SQL check through the real BFF session. *Stub* is controlled-provider evidence. *Real OpenAI* marks the live AI
calls. Nothing here comes from unit or integration test fixtures, and nothing comes from real Meta.

## Results

| Case | Result | Browser | API / DB / stub | Notes |
| --- | --- | --- | --- | --- |
| FLOW-01 AI draft, edit, review preview, nothing sent | **PASS** | OWNER on the due customer: *Obtener recomendación* (real OpenAI, `REPEAT_PURCHASE`, 91 %) → *Generar borrador* (real OpenAI). The panel says "Dokene no ha enviado nada" and "Edición local: no se guarda ni se envía". After an edit, *Revisar y enviar* shows the masked recipient, action, template, origin *Borrador de IA*, language and the final text, equal to the edit | No message row, no send attempt, no stub call after generation | While `REPEAT_PURCHASE` was unmapped the review warned that it cannot be submitted, and a submit was refused with "No hay una plantilla configurada…", keeping the text (observation O1) |
| FLOW-02 Submit for approval | **PASS** | "Mensaje enviado a aprobación. Aún no se ha enviado al cliente." Review shows *Revisión 1*, *Pendiente de aprobación* and the rendered template body. *Registrar seguimiento* stays available and saved while a message was open | `PENDING_APPROVAL` v1, body equal to the edit byte for byte, `approval` null, `attempts` empty, events `[SUBMITTED]`; 0 attempts, 0 stub calls | *Copiar borrador* is offered, but this browser denies `clipboard-write`, so it reports "No se pudo copiar el borrador" (harness limitation) |
| FLOW-03 Approve the current revision | **PASS** | After a full reload the history still shows the pending message. *Aprobar* → "Se aprobará exactamente el texto mostrado. Aprobar no envía el mensaje." → "Mensaje aprobado. Aún no se ha enviado." No request to `/sends` | `APPROVED` v2, `sentAt` and `providerMessageId` null, no attempts. Approval row decided by the OWNER membership, with the fingerprint stored. Audit `MESSAGE_SUBMITTED`, `MESSAGE_APPROVED` for that membership; no fingerprint, send key or token in any response | The approval body carries the reviewed `templateRevision` and the message ETag (seen on the FLOW-05 approvals) |
| FLOW-04 Reject, cancel, correct | **PASS** | *Corregir* on an approved message cancels it and opens an editable copy; the edited copy is a **new** message that needs a new approval. *Rechazar* with a note → "Mensaje rechazado…". *Cancelar* on a pending message → "Mensaje cancelado…" | `PUT`/`PATCH /api/messages/{id}` → 405; the cancelled message keeps its original body. Approve or cancel on `CANCELLED`/`REJECTED` → 409 `INVALID_TRANSITION`; send → 409 (refused). 0 attempts, 0 stub calls | Send on a terminal message answers `OUTBOUND_DISABLED_GLOBALLY` while the global switch is off (O3) |
| FLOW-05 Stale message, preview, policy and integration | **PASS** | Two tabs on one pending message: tab B approves; stale tab A's reject → "Este mensaje cambió: ahora está Aprobado." Customer renamed (changes the rendered template) → approve on the old preview refused, new preview shown. Manual follow-up saved in tab B → tab A's submit refused, text kept; a later policy change → "La situación del cliente cambió…". After a credential rotation the approved message shows "La aprobación ya no cubre lo que se enviaría…" and no *Enviar* | Stale reject keeps `APPROVED`. Old `templateRevision` → 409 `STALE_VERSION`, no approval row. Stale policy `If-Match` → 409 `STALE_VERSION`. `/sends` after rotation → 409 `APPROVAL_INVALIDATED`, still `APPROVED`, 0 attempts, 0 stub calls | Window W1. The first live draft was refused by the model (`MODEL_REFUSED`); regenerating worked (O5) |
| FLOW-06 Switch workspace or customer mid-operation | **PASS** | Norte with an edited AI draft and a pending message whose approve confirmation was open → switch to Sur: only Sur data; a DOM mutation observer saw 0 frames with Norte's name, phone, draft or message. Back to Norte: the AI draft and the review are discarded (documented: ADR 0026, issue 97), and the stored message is still listed and reopenable. Customer switch with the send confirmation open → the new customer shows no message, group or button of the old one | Message untouched by the switch; tenant B 0 messages | Approve confirmed and workspace switched in the same task: the client aborted the response and Sur showed nothing. The approval had reached the server and committed **in Norte**, and Norte's history shows *Aprobado, sin enviar* on return (O6) |
| FLOW-07 OWNER, OPERATOR and VIEWER | **PASS** | OWNER: FLOW-01 to FLOW-04. OPERATOR in window W1: AI draft → submit → approve its own message (no second approver) → *Enviar* → "Envío procesado. Estado actual: Enviado."; history row *Aceptado por el proveedor / Aún sin confirmación de entrega*. VIEWER: "Tu rol solo permite consultar mensajes.", no action buttons; AI and follow-up actions disabled | OPERATOR message `SENT`, attempt 1 `ACCEPTED`. Created, approved and audited (`SUBMITTED`, `APPROVED`, `SEND_REQUESTED`, `SENT`) by the operator membership. One stub send: the body parameter equals the approved body. VIEWER: approve, reject, cancel, send, submit, AI draft and compose locales → 403; message and ETag unchanged; no audit row | A separate ADMIN identity was not exercised (setup note 2); OWNER and ADMIN have the same `MESSAGE_*` permissions |

Totals in `issue-334-evidence/outputs.txt`: 70 case PASS lines, 0 FAIL, 8 case NOTE lines, plus SETUP, WINDOW and
CLEANUP lines. Provider send calls during the whole suite: **1**, the OPERATOR send in window W1, to the loopback stub.

### Controlled deviation (approved by the user in the session)

Two checks need the global switch on: refusing a send whose approval no longer matches (`APPROVAL_INVALIDATED`, policy
step 5b runs after the global gate) and an OPERATOR sending its own message. With the user's approval,
`window-open334.sh` restarted the backend with the global switch on. The script first asserts that the Graph base URL
is the loopback stub; every token was a synthetic canary. A watchdog would have closed the window after 10 minutes.

- **W1**: global ON from 23:54:36Z to 23:57:05Z (about 2.5 minutes). Tenant A outbound was switched on, the credential
  was rotated and ownership re-proven (the integration change after approval). Then the FLOW-05 invalidation check and
  the FLOW-07 OPERATOR send ran.
- `window-close334.sh` switched tenant A off, restarted with the global switch off and verified both after the restart
  (`global off`, tenant A `enabled/proven/outbound = true/true/false`).

The one stub send in the window is the OPERATOR's message. No other message was approved while the switch was on.

## Defects and observations

No new defect. Searched first: open `phase-3` issues (only the #332 suites) and title searches for the observations
below; none covers them.

| Id | Observation | Classification |
| --- | --- | --- |
| O1 | The review says the message cannot be submitted (no approved template for the action), but *Enviar a aprobación* stays enabled. Submitting is refused by the server with a clear message, and the text is kept | UX observation for #338. Safe: nothing is created |
| O2 | After a preview-only change (a customer rename), the refused approval announces "Este mensaje cambió: ahora está Pendiente de aprobación." The status did not change, and the alert does not say what did (the preview) | UX wording for #338. Re-review is still forced and the new preview is shown |
| O3 | On a `CANCELLED` or `REJECTED` message, `/sends` answers `OUTBOUND_DISABLED_GLOBALLY` while the global switch is off, not `INVALID_TRANSITION` | By design: ADR 0023 evaluates the global gate first. Safe |
| O4 | After a send, the customer leaves the queue (`LAST_OUTBOUND_MESSAGE` timing, next date in 30 days). A manual follow-up also makes a pending submit fail with `FOLLOW_UP_INELIGIBLE` rather than `STALE_VERSION` | Expected behaviour; the guidance shown ("El cliente no está elegible…") is accurate |
| O5 | The live model once declined to draft because the only purchase is named "Compra sintetica QA333" | Live AI behaviour on synthetic data; handled safely, regeneration succeeded |
| O6 | A switch does not retract an approval the operator already confirmed: the server commits it in the original tenant and the client ignores the late reply | Correct for an explicit action; nothing is applied to the new tenant and the original history shows the truth |

## Setup notes

1. The #333 stack had been stopped. It was restarted from the same volume. `stack333.sh` needs `bash` (it lost its
   execute bit) and the app is reached on `http://localhost:5173` (the BFF on 8080 has no root page).
2. Keycloak did not persist the four extra users that `fixtures333.sh` created through the admin API (`testadmin`,
   `testowner2`, `testoperator2`, `testnone`): their logins failed after the restart. The seeded users have fixed ids
   and still work. Restoring the others would create new subjects without their memberships, so this suite used the
   seeded OWNER for the OWNER/ADMIN journey. Later suites that need them should re-run `fixtures333.sh` and re-grant the
   memberships.
3. Every backend restart ends BFF sessions; the browser re-entered through the surviving Keycloak SSO session. Users were
   switched with `kc-logout.sh` (issue 307 residual).
4. Harness corrections, not product results: a first FLOW-04 run read the ETag in a subshell and sent an empty
   `If-Match` (400 `INVALID_INPUT`), and a FLOW-07 run failed on the `testadmin` login. Those lines were removed and the
   checks rerun (`flow04-transitions334.sh`, the OWNER variant of `flow07-roles334.sh`); the committed scripts are the
   corrected ones.
5. Customer state was changed through the API only to set up cases (template mapping, due dates, a rename). Cleanup
   (`cleanup334.sh`) cancelled every open message and restored the customer's name, due date and the second customer's
   state. Final counts: 15 `CANCELLED` (10 from #333), 1 `REJECTED`, 1 `SENT`; 1 send attempt; tenant A outbound off.

## Reproducing

From the #333 stack (see its report), with real OpenAI enabled only if authorised: run `map-repeat334.sh`, then the
browser steps for FLOW-01 to FLOW-03, `flow04-probes334.sh` and `flow04-transitions334.sh` after the FLOW-04 browser
steps, the two-tab browser steps for FLOW-05, `window-open334.sh W1 rotate` → browser steps (FLOW-05 invalidation,
FLOW-07 OPERATOR) → `window-close334.sh W1` (only with the loopback stub, which both scripts assert),
`flow07-roles334.sh`, the VIEWER and FLOW-06 browser steps, and `cleanup334.sh`. The scripts are in
[`issue-334-evidence/scripts/`](issue-334-evidence/scripts/), and the recorded run is
[`issue-334-evidence/outputs.txt`](issue-334-evidence/outputs.txt).

## Limitations

- Controlled provider mode: `SENT` here means the stub accepted the call. Delivery and callbacks are suite #336.
- The in-flight switch test ran with a 30 ms gap on a local stack, so the approval had already reached the server.
  A slower network could show the other order (aborted before the server sees it); both are safe.
- Copy-to-clipboard success could not be observed in this browser (`clipboard-write` denied).
- Live AI output is nondeterministic. The drafts and the refusal are evidence of this run only.
