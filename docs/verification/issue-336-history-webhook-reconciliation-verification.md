# Issue #336: Phase 3 manual QA, history, webhook outcomes and reconciliation

Suite of the Phase 3 manual QA epic [#332](https://github.com/stevdrey/dokene/issues/332). Cases P3-MQA-HIST-01 to
HIST-06.

**Verdict: PASS (scoped)** for candidate `9504dca733d1a04e0c80972845f4e0e34d81a019` (`main`, 2026-11-17), on the local
stack with the loopback Graph stub and signed synthetic Meta callbacks. 6 of 6 cases PASS, 0 FAIL, 0 BLOCKED, no new
defects. Every delivery change came from a signed callback over real HTTP and moved the same message forward. No
callback, replay, refusal or T12 resolution made a provider request, added an attempt or created a message. Forged,
malformed and unmatched callbacks changed nothing and never set a cookie. This is not real Meta evidence: that is
suite [#339](https://github.com/stevdrey/dokene/issues/339).

## Environment

| Item | Value |
| --- | --- |
| Candidate | `9504dca`. It differs from the #335 candidate `571fff0` only under `docs/`. `backend/`, `frontend/`, `scripts/` and `compose.yaml` are byte-identical, so the #333 images were reused |
| Stack | The #333 compose project `dokene-qa333` (disposable volume), worktree moved to the candidate |
| Browser | Claude desktop built-in browser (Chromium) on `http://localhost:5173`, driven through the real UI as the OWNER `testuser` |
| AI provider | `fake`. No AI call was needed: every message is a manual, synthetic text |
| WhatsApp provider | Loopback Graph API stub (`docs/verification/issue-149-evidence/scripts/GraphStub.java`) with synthetic canary tokens. Send modes used: `accept`, `error401canary` (401, refused) and `paused` (an id with a non-accepted status, so the outcome is unknown but the id is kept) |
| Callbacks | Meta-shaped `whatsapp_business_account` status bodies with synthetic ids, numbers and timestamps, signed with HMAC-SHA256 over the raw bytes using the #333 synthetic canary app secret, and posted with `curl` to `POST /webhooks/whatsapp` on the real stack (no cookie jar, no CSRF) |
| Switches | Global and tenant outbound **off**, except the stub-only window W1 below. Webhooks need no switch |
| Fixtures | Tenant A *QA Café Norte*: OWNER `testuser`, OPERATOR `testoperator`. Tenant B *QA Café Sur* (`testuser` is ADMIN there), with its own verified number, for the cross-tenant checks. 15 disposable customers named `QA336 …` (CL test-range numbers `+5698336xxxx`, WhatsApp consent granted, due today), one message per state, plus one customer with a 27-message history |

Evidence kinds are kept apart. *Browser* means a person-driven action in the real UI, read back as page text.
*API/DB* means an authenticated HTTP call through the real BFF session (CSRF, tenant header) or a read-only SQL check.
*Webhook* means a signed synthetic callback over real HTTP to the public route. *Stub* is controlled-provider
evidence: its request log counts every provider call. Nothing here comes from unit or integration test fixtures, and
nothing comes from real Meta.

## Results

| Case | Result | Browser | API / DB / webhook | Notes |
| --- | --- | --- | --- | --- |
| HIST-01 History per state | **PASS** | Read in the workbench history of each customer. No message: "Este cliente no tiene mensajes todavía." Pending: *Aún no enviado / Pendiente de aprobación*. Approved: *Aún no enviado / Aprobado, sin enviar*. Cancelled: *Cancelado / No se enviará al cliente*. Sent: *Aceptado por el proveedor / Aún sin confirmación de entrega*. Delivered: *Entregado / Entregado al teléfono del cliente*. Read: *Leído / El cliente abrió el mensaje*. Failed: *Fallido / No se entregó al cliente.* Unknown: *Resultado sin confirmar* | Each label matches the database row (`status`, `outcome_unknown`, `delivered_at`, `read_at`, `failure_category`) at the time of reading. Expanded rows show the server's event rows (actor *Miembro*, *Sistema* or *Proveedor*) and timestamps | Acceptance is never shown as delivery. Before a refresh, the page keeps the last server read; it never guesses ahead (HIST-02) |
| HIST-02 Signed callback advances the same message | **PASS** | H2 open in the browser before the callback, showing *Aceptado por el proveedor*. After the delivered callback and before any refresh it still showed that. *Actualizar historial* → *Entregado*. After the read callback, the row's *Actualizar* → *Leído*, live region "El estado cambió: ahora Leído.", times *Aceptado* 11:08, *Entregado* 11:14, *Leído* 11:14, timeline *Proveedor · Enviado → Entregado* and *Proveedor · Entregado → Leído* | Each callback: 200, no `Set-Cookie`, one inbox row `APPLIED`, one applied `DELIVERY_UPDATED` event (`SENT>DELIVERED`, then `DELIVERED>READ`). Same message id, attempts still 1 `ACCEPTED`, no new message, no stub request. Detail and list API agree with the database | – |
| HIST-03 Replay, reorder, duplicate | **PASS** | H3 after all replays: one row *Leído*; times *Aceptado* and *Leído* only; timeline one *Proveedor · Enviado → Leído*. H1D after 6 concurrent duplicates: one row *Entregado* | Read before delivered → `READ`. Late delivered → acknowledged, inbox `IGNORED`, status stays `READ`. Byte-identical replay and a read re-sent with another timestamp → no new inbox row, no event, same version. One body with a duplicated delivered, a stale sent and a repeated read → only the unseen state (sent) adds a row, `IGNORED`. Failed after read → `IGNORED`, no failure category. 6 concurrent identical delivered callbacks → 6 × 200, one inbox row, one applied event. No regression, no extra attempt, stub request or message | Dedup identity is (tenant, provider message id, state). Late reports for a new state are kept as not-applied events (O4) |
| HIST-04 Forged, unmatched, malformed | **PASS** | – | 403 for: signature from another secret, no signature header, `sha1=`, non-hex `sha256=`, two signature headers, a valid signature of another body (status changed after signing), a body one byte over the 4 MiB limit. 400 for: malformed JSON, a supplied `tenantId` member, another `object` type, a non-epoch timestamp. 200 with nothing stored for an unknown phone number id and an unsupported status value. A wrong provider reference (tenant A) and H4's real reference reported through tenant B's verified number → 200, parked as `PENDING` in their own tenants. After the 15-minute park window both became `UNMATCHED` with the provider id cleared. H4 stayed `SENT` with the same version and events. No response carried `Set-Cookie`; a client that only called the webhook holds no cookie and `/api/session` answers 401. A wrong verify token on the GET challenge → 403, no echo | Cross-tenant: the tenant comes only from the verified number, so tenant B could never touch tenant A's message. One harness error was superseded (setup note 2) |
| HIST-05 Failure and unknown outcome | **PASS** | Synchronous 401: *Fallido*, *Error de autenticación con el proveedor*, "La integración no pudo autenticarse con el proveedor. Pide a un administrador que revise la integración.", "Este mensaje no se reenviará automáticamente." Failed callback 131026: *Destinatario inválido*, "El número no es válido para WhatsApp. Verifica el teléfono del cliente antes de redactar otro mensaje." Unknown: *Resultado sin confirmar*, "No lo reenvíes; actualiza o pide a un administrador que lo resuelva." After T12: *Error desconocido*, "No se conoce la causa. Revisa el historial del mensaje antes de volver a contactar al cliente.", timeline *Miembro · Enviando → Fallido*. Every failed or unknown row offers only *Actualizar* and *Abrir en revisión*; no send or resolve control. No canary text in the page | Stub 401 → `FAILED AUTHENTICATION`, attempt `FAILED_PERMANENT`, no canary in the API body. Failed callback → `FAILED INVALID_RECIPIENT`. **Reconciliation:** a delivered callback for an unknown outcome whose id was kept → `DELIVERED`, `outcomeUnknown` cleared, the attempt stays `OUTCOME_UNKNOWN`, no resend. T12 (API only): OPERATOR → 403; OWNER without `If-Match` → 400 `INVALID_INPUT`; OWNER with a stale ETag → 409 `STALE_VERSION`; OWNER with the current ETag → 201 `FAILED` (`UNKNOWN`), events `SEND_OUTCOME_UNKNOWN, SEND_FAILED`. A resend on the unknown outcome → 409. No stub request at any step | A delivered callback after T12 is acknowledged but does not reopen `FAILED` (O2). With the switch off the resend refusal reports the global gate (O5) |
| HIST-06 Pages and pending navigation | **PASS** | H6 (27 messages): first page 20 rows with *Cargar más*; after it, 27 unique rows ("Se cargaron 7 mensajes más."), no more *Cargar más*; the 27 row ids equal the database set. With a test-only fetch delay of 5 s: a held history refresh of H6, then H6N opened → H6N shows only its own message. A held *Cargar más* of H6, then H6N opened → the page request was aborted; H6N shows only its own message. H6N's row detail and events held, then the workspace switched to *QA Café Sur* → both requests aborted; the tenant B page holds no `QA336` name, tenant A message id, raw callback member, provider id or canary | Keyset pages of 20: page 1 newest first with a cursor. Between pages one message was cancelled and a new one submitted: page 2 has 6 rows, no duplicate, and pages 1 and 2 together equal the 26 earlier messages. A fresh first page shows the new message on top and the cancelled one updated. Tenant B: A's customer filter → 200 with no rows, A's message detail → 404 without A data, B's list holds no A message. List, detail and events responses carry no raw callback member, signature or canary | The fetch delay is test instrumentation installed in the page (inspection only) |

Totals in `issue-336-evidence/outputs.txt`: 74 case PASS lines (HIST-01 to HIST-06), 0 FAIL, 3 `HARNESS-ERROR`
lines (superseded, setup notes 1 and 2), plus SETUP, WINDOW, END and CLEANUP lines (138 lines in all, 130 of them PASS).

### Controlled deviation: one stub-only global-ON window

Sending needs the global switch on; delivery callbacks do not. The suite reused the #334 window scripts
(`window-open334.sh` and `window-close334.sh`, with lock, tokenised watchdog and fail-closed close) through the #335
stack wrapper, after the user approved a stub-only window for this suite. Both scripts assert that the Graph base URL is
the loopback stub; every token was a synthetic canary.

- **W1**: global ON for about one minute (watchdog 20 minutes). `w1-sends336.sh` made the 9 sends the cases need: 6
  accepted, 1 refused by the stub (401) and 2 answered `paused`. The close verified global OFF and tenant A OFF after the
  restart. Every other step ran with both switches off.

Provider send requests during the suite: **9**, all to the loopback stub (stub send count 13 → 22), all inside W1. The
count stayed at 22 through every callback, replay, refusal, T12 resolution and the cleanup.

## Defects and observations

No new defect. Searched first: open `phase-3` issues (only the #332 suites) and title searches for the observations
below; none covers them.

| Id | Observation | Classification |
| --- | --- | --- |
| O1 | The message history is shown only in the follow-up workbench, whose queue lists due customers. A send makes the customer not yet due, so sent, delivered and read histories leave the queue; the suite put those customers back on today's queue (follow-up policy only, `due336.sh`) to inspect them. The customer profile has no history | UX observation for #338. Not a data problem: the API history is complete |
| O2 | After a T12 resolution to `FAILED`, a later provider *delivered* report is acknowledged and ignored, so the history keeps *Fallido* although the provider reported delivery | By design: ADR 0023 makes `FAILED` terminal and T12 an operator decision. The guidance after T12 ("No se conoce la causa…") does not claim the customer did not receive it. Possible product follow-up |
| O3 | When a callback reconciles an unknown outcome, *Aceptado por el proveedor* shows the delivery time, since the acceptance time was never known | By design (`sentAt` takes the report time when none is stored) |
| O4 | A late report for a state not seen before (delivered after read, sent after read, failed after read) is stored as `IGNORED` in the inbox and as a not-applied `DELIVERY_UPDATED` event that bumps the message version. The browser timeline lists only applied events | By design (spec 0001: reports never throw; a non-applied event keeps the audit trail) |
| O5 | With the global switch off, a resend attempt on an unknown outcome is refused with `OUTBOUND_DISABLED_GLOBALLY` (the global gate comes first). #335 SEND-06 checked the same refusal with the switch on: `INVALID_TRANSITION` | Expected order of checks; either way no attempt and no provider request |
| O6 | A read reported before any delivered report leaves `deliveredAt` empty; the history shows *Aceptado* and *Leído* without an *Entregado* time | Accurate: no delivery time was reported |

## Setup notes

1. **Harness error, setup (superseded).** The first `fixtures336.sh` run sent the cancellation note as `reason`; the
   request field is `note`, so two cancellations got 400 and the 26-message history was not built. The helper was
   fixed and `fixtures336-h6.sh` completed both steps. The two lines are marked `HARNESS-ERROR` in `outputs.txt`.
2. **Harness error, HIST-04 (superseded).** For the 4 MiB body `curl` sent `Expect: 100-continue`, so the parser read
   the interim `100` status and recorded FAIL. `wh_post` now disables `Expect`; `hist04-big336.sh` reran the check:
   403, nothing stored. The first line is marked `HARNESS-ERROR`.
3. The #333 stack was still running. Its worktree was moved to the candidate. The Keycloak SSO session had expired, so
   the browser signed in again as the seeded `testuser` on the local Keycloak.
4. The browser read each history as page text through a small inspection helper (`innerText` of the history section,
   row ids from `aria-controls`); every click was a real click on the page.
5. Callback bodies are written to `$OUTD` and contain only synthetic values. The signing key reaches `python3` through
   the environment, never an argument. Webhook calls carry no session or CSRF value; the BFF calls go through the
   protected curl wrapper, which `qa336.sh` loads itself.
6. Cleanup (`final336.sh`): the 4 still-open suite messages were cancelled, the 15 `QA336` customers were archived,
   and the stub was reset to `accept`. End checks: global and tenant A outbound off, stub send count still 22. Inbox at
   the end: tenant A `DELIVERED` 3 applied, 2 ignored, 1 unmatched; `READ` 2 applied; `FAILED` 1 applied, 1 ignored;
   `SENT` 1 ignored; tenant B `READ` 1 unmatched. Final database counts (all suites on this stack):
   - messages: 57 `CANCELLED`, 2 `DELIVERED`, 5 `FAILED`, 2 `READ`, 1 `REJECTED`, 13 `SENT`;
   - attempts: 17 `ACCEPTED`, 1 `FAILED_PERMANENT`, 4 `OUTCOME_UNKNOWN`;
   - tenant A: enabled, proven, outbound off.

## Reproducing

From the #333 stack (see its report), with the global switch off:

1. `fixtures336.sh` (and, only after the setup-note-1 failure, `fixtures336-h6.sh`).
2. Open W1: `OUTD=~/qa336/out STACK333=…/issue-335-evidence/scripts/stack335.sh WINDOW_TTL=1200 window-open334.sh W1`;
   run `w1-sends336.sh`; close W1: `window-close334.sh W1`.
3. `due336.sh H1S H1D H2 H3 H4 H5W H5F` to put the sent customers back on the workbench queue.
4. HIST-01 browser reads. HIST-02: open H2 in the browser, `hist02-336.sh delivered`, refresh, `hist02-336.sh read`,
   refresh the row.
5. `hist03-336.sh`, then the H3 and H1D browser reads.
6. `hist05-336.sh callbacks`, the browser reads, `hist05-336.sh t12`, the browser read of H5U.
7. `hist04-336.sh`, then after the 15-minute park window `hist04-336.sh late`.
8. `hist06-336.sh`, then the HIST-06 browser steps with the fetch delay.
9. `final336.sh`.

The scripts are in [`issue-336-evidence/scripts/`](issue-336-evidence/scripts/), and the recorded run is
[`issue-336-evidence/outputs.txt`](issue-336-evidence/outputs.txt).

## Limitations

- Controlled provider mode and synthetic callbacks: they follow Meta's documented status shape and signature scheme,
  but real Meta delivery and its callback timing are suite #339.
- An unknown outcome without a provider id (the hang and crash cases of #335) cannot be matched by any callback; only
  T12 resolves it. Reconciliation here covers the case where the provider returned an id.
- The park and retry path was observed through the database states (`PENDING`, then `UNMATCHED`); the abandon path
  (one day) and the 30-day retention purge were not waited out.
- Pending requests were simulated in the browser with a page-level fetch delay. Concurrency ran on one local machine
  against one backend instance.
