# Issue #335: Phase 3 manual QA, safe manual sends, policy gates and duplicate suppression

Suite of the Phase 3 manual QA epic [#332](https://github.com/stevdrey/dokene/issues/332). Cases P3-MQA-SEND-01 to
SEND-07.

**Verdict: PASS (scoped)** for candidate `571fff00bbc50d1ab3d2e701572dadcddd142a50` (`main`, 2026-11-17), on the local
stack with the loopback Graph stub. 7 of 7 cases PASS, 0 FAIL, 0 BLOCKED, no new defects. Every deterministic refusal
made zero provider requests and wrote no attempt row. Controlled sends kept one logical attempt per message, and the
stack recorded acceptance, failure and unknown outcomes as separate states. This is not real Meta evidence: that is
suite [#339](https://github.com/stevdrey/dokene/issues/339).

## Environment

| Item | Value |
| --- | --- |
| Candidate | `571fff0`. It differs from the #333 candidate `3807963` only under `docs/`. `backend/`, `frontend/`, `scripts/` and `compose.yaml` are byte-identical, so the #333 images were reused (backend `sha256:66e6c0646b08`, frontend `sha256:3353423880b4`) |
| Stack | The #333 compose project `dokene-qa333` (disposable volume), worktree moved to the candidate |
| Browser | Claude desktop built-in browser (Chromium) on `http://localhost:5173`, driven through the real UI as the OWNER `testuser` |
| AI provider | `fake`. No AI call was needed: every message is a manual, synthetic text |
| WhatsApp provider | Loopback Graph API stub (`docs/verification/issue-149-evidence/scripts/GraphStub.java`) with synthetic canary tokens. Fault injection through its control files: `send=hang` (never answers within the deadline) and `delay.ms`. No real Meta call is possible in this mode |
| Switches | Global and tenant outbound **off**, except the two stub-only windows W1 and W2 below |
| Fixtures | Tenant A *QA Café Norte*: OWNER `testuser`, OPERATOR `testoperator`. Tenant B *QA Café Sur* (`testuser` is ADMIN there) for the cross-tenant probe. 21 disposable customers named `QA335 …` (CL test-range numbers `+5698335xxxx`, WhatsApp consent granted, due today), one message per case. Templates `REPEAT_PURCHASE` and `GENERAL_FOLLOW_UP` mapped and refreshed against the stub |

Evidence kinds are kept apart. *Browser* means a person-driven action in the real UI. *API/DB* means an authenticated
HTTP call through the real BFF session (CSRF, tenant header) or a read-only SQL check. *Stub* is controlled-provider
evidence: its request log counts every provider call. Nothing here comes from unit or integration test fixtures, and
nothing comes from real Meta.

## Results

| Case | Result | Browser | API / DB / stub | Notes |
| --- | --- | --- | --- | --- |
| SEND-01 Global OFF, then tenant OFF | **PASS** | Global OFF: *Abrir en revisión* → *Enviar* → *Confirmar envío* on an approved message shows "Los envíos están desactivados en la plataforma."; the message stays *Aprobado* | Global OFF: OWNER and OPERATOR sends → 409 `OUTBOUND_DISABLED_GLOBALLY`. A repeated key and three rapid sends are refused the same way, with no attempt rows and no stub calls. Refusal bodies carry only `code`, `status`, `title`, `type`. Global ON with tenant A OFF (W1, stub stack only) → 409 `OUTBOUND_DISABLED_FOR_TENANT`, 0 attempts, 0 stub calls | The UI offers *Enviar* while the switch is off; the server refuses (O1) |
| SEND-02 Contact and customer changes after approval | **PASS** | – | Each change made after approval, then a send: consent revoked → `NO_CONTACT_CONSENT`; do-not-contact → `DO_NOT_CONTACT`; customer archived → `CUSTOMER_ARCHIVED`; number replaced → `CONTACT_NOT_OWNED`; follow-up snoozed 7 days (snooze route) → `FOLLOW_UP_INELIGIBLE`; next date moved 7 days out → `FOLLOW_UP_INELIGIBLE`. All 409, 0 attempts, 0 stub calls, message still `APPROVED`. The same message id sent or read from tenant B → 404 `NOT_FOUND`, with no tenant A name or number in the body | Five distinct reasons, no data from other records. One harness error was superseded (setup note 2) |
| SEND-03 Integration, mapping and role changes after approval | **PASS** | – | Operator who approved is demoted to VIEWER → their send is 403 `FORBIDDEN`; with the role restored the same message sends once (authority is re-read at dispatch). Mapping disabled → `TEMPLATE_NOT_MAPPED`. Mapping pointed at another template → `APPROVAL_INVALIDATED`. Integration disabled → `INTEGRATION_DISABLED`. Credential rotated (unproven) → `INTEGRATION_DISABLED`; after re-proof, both older approvals → `APPROVAL_INVALIDATED` (sender revision changed). 0 attempts and 0 stub calls for every refusal | Re-enabling a disabled integration cleared its ownership proof and needed a new proof (O3) |
| SEND-04 One explicitly approved manual send | **PASS** | *Aprobar* → "Se aprobará exactamente el texto mostrado. Aprobar no envía el mensaje." → "Mensaje aprobado. Aún no se ha enviado." Then *Enviar* → *Confirmar envío* ("Se enviará exactamente el texto mostrado arriba.") → "Envío procesado. Estado actual: Enviado."; history *Aceptado por el proveedor / Aún sin confirmación de entrega*, *Intento 1 · Aceptado* | `SENT`, synthetic provider id, `deliveredAt` and `readAt` null, one attempt `ACCEPTED`, one stub request whose body parameter equals the approved body. Events `SUBMITTED, APPROVED, SEND_REQUESTED, SEND_ATTEMPT_STARTED, SENT`. The runtime DB role has only `INSERT` on `outbound_message_events`, and `audit_events` has immutable and no-truncate triggers | Provider acceptance is shown apart from delivery. Delivery callbacks are suite #336 |
| SEND-05 Double click, key replay, concurrency | **PASS** | Double click on *Enviar* opens one confirmation (no request). Double click on *Confirmar envío* with a 1.5 s stub delay: a fetch counter saw **one** `POST /sends`; one *Enviado*, *Intento 1* | Key K: first send 201 `SENT`; same key and `If-Match` → 200 replay with the same provider id, no new attempt or stub call. Same key with the current `If-Match`, or on another approved message → 409 `IDEMPOTENCY_KEY_REUSED` (that message untouched). A body payload → 400 `INVALID_INPUT`. A new key on the sent message → 409 `INVALID_TRANSITION`. 8 concurrent sends, distinct keys: one 201, seven 409 `STALE_VERSION`. 8 concurrent sends with one key: one 201, seven 200 replays. Each message: one attempt, one stub call | Stub delay 1.5 s kept the first call open while the others arrived |
| SEND-06 Unknown outcome near acceptance | **PASS** | Stub `send=hang`: *Confirmar envío* shows *Cargando…* with *Enviar* disabled. After the 10 s deadline the panel says "El proveedor no confirmó el envío. Un administrador debe resolverlo." and *Intento 1 · Resultado desconocido*. The history says *Resultado sin confirmar*: "No lo reenvíes; actualiza o pide a un administrador que lo resuelva." Only *Actualizar* is offered (no *Enviar*, no resolve control); *Actualizar* re-reads without a send | Hang: `SENDING`, `outcomeUnknown=true`, attempt `OUTCOME_UNKNOWN`, no provider id; a resend with a new key → 409 `INVALID_TRANSITION`. Crash (W2): backend killed 3 s into a hanging provider call. After restart the claim is kept (attempt `STARTED`, `SENDING`) and nothing is resent; a manual resend → 409. Recovery turned the claim into `OUTCOME_UNKNOWN` after the 2 m claim timeout without a provider call. T12 is API-only: OPERATOR → 403, outcome `SENT` → 400 `INVALID_INPUT`, OWNER `FAILED` → `FAILED` (events end `SEND_OUTCOME_UNKNOWN, SEND_FAILED`), no stub call; a send afterwards → `INVALID_TRANSITION` | The alert after the deadline reads "Envío procesado. Estado actual: Enviando." (O2) |
| SEND-07 Contact interval, no premature send or permanent block, end state | **PASS** | – | W2, min interval 3 m: first message sent. Then the customer was rescheduled, and a new message drafted and approved (no permanent block). Its send inside the interval → 409 `CONTACT_FREQUENCY_EXCEEDED`, 0 attempts, 0 stub calls. The same message after 186 s → 201 `SENT`, one attempt. A customer whose earlier message ended `FAILED` (T12) got a new message sent at once (a failure starts no interval). End: global OFF, tenant A OFF, no policy override in the backend env (default 24 h interval), and an approved message is refused again with `OUTBOUND_DISABLED_GLOBALLY` | The 24 h baseline is the `application.yml` default, confirmed by the absence of any override; it was not waited out |

Totals in `issue-335-evidence/outputs.txt`: 62 case PASS lines, 0 FAIL, 4 `HARNESS-ERROR` lines (superseded, setup
notes 2 and 3), plus SETUP, WINDOW and CLEANUP lines. Provider send calls during the suite: **12**, all to the loopback
stub (stub send count 1 → 13). 10 were accepted sends of approved messages:

- the role control;
- SEND-04 and the SEND-05 double click;
- three SEND-05 API messages;
- three SEND-07 sends;
- the send to the then-due customer of setup note 2.

The other 2 are the hung calls of SEND-06.

### Controlled deviation: stub-only global-ON windows

Every check after the global gate needs the global switch on. The suite reused the #334 window scripts
(`window-open334.sh` and `window-close334.sh`, with lock, tokenised watchdog and fail-closed close), through a suite
wrapper `stack335.sh`. Both scripts assert that the Graph base URL is the loopback stub; every token was a synthetic
canary.

- **W1**: global ON from 03:24:00Z to 03:29:11Z (about 5 minutes; watchdog 30 minutes). It covered SEND-01 tenant OFF,
  SEND-02, SEND-03, SEND-04, SEND-05 and the SEND-06 hang. The close verified global OFF and tenant A OFF after the
  restart, and the baseline policy.
- **W2**: global ON from 03:30:28Z to 03:34:31Z (about 4 minutes; watchdog 20 minutes), with three overrides that
  `stack335.sh` applies only while the global switch is on:
  - `DOKENE_MESSAGING_MIN_CONTACT_INTERVAL=3m`;
  - `DOKENE_MESSAGING_RECOVERY_CLAIM_TIMEOUT=2m`;
  - `DOKENE_MESSAGING_RECOVERY_INTERVAL=15s`.

  It covered the SEND-06 crash and recovery, and SEND-07. The close restarted with the global switch off, and with it
  the default policy; `final335.sh` verified this.

## Defects and observations

No new defect. Searched first: open `phase-3` issues (only the #332 suites) and title searches for the observations
below; none covers them.

| Id | Observation | Classification |
| --- | --- | --- |
| O1 | With the global switch off, the review still offers *Enviar* and *Confirmar envío*. The refusal comes after confirming ("Los envíos están desactivados en la plataforma.") | UX observation for #338. Safe: refused before any write |
| O2 | After the dispatch deadline the alert says "Envío procesado. Estado actual: Enviando." while the outcome is unknown. The panel and history then explain it correctly ("El proveedor no confirmó el envío…") | UX wording for #338 |
| O3 | Re-enabling a disabled integration cleared its ownership proof; outbound needed a new proof | Conservative and safe |
| O4 | After a T12 resolution the message is `FAILED` (`failureCategory=UNKNOWN`), and its attempt row keeps `OUTCOME_UNKNOWN` | By design: the attempt records what was known at the call; the resolution is a separate event |
| O5 | Archiving a customer leaves its approved message `APPROVED` until someone cancels it; any send is refused with `CUSTOMER_ARCHIVED` | Safe. Possible UX follow-up for #338 (an archived customer's open message stays listed) |
| O6 | Losers of a concurrent send with distinct keys get `STALE_VERSION` rather than `INVALID_TRANSITION` | Expected: they carry the pre-send `If-Match`. A replay with the original key still answers the current state |

## Setup notes

1. The #333 stack was still running. Its worktree was moved to the candidate. The extra admin-API users were not
   needed: the OWNER and OPERATOR seeded users cover every case.
2. **Harness error, SEND-02 (superseded).** The first ineligibility step used `PUT /follow-up-policy` with
   `snoozedUntil`. That request has no snooze field (snoozing is `PUT /follow-up-snooze`), and its
   `explicitNextDate: null` made the customer due by cadence. The approved message was therefore sent legitimately, to
   a customer who was due. The three lines this produced are marked `HARNESS-ERROR` in `outputs.txt`.
   `w1-ineligible335.sh` reran the check with two fresh customers: the snooze route and a future explicit date. Both
   were refused with `FOLLOW_UP_INELIGIBLE`. `w1-gates335.sh` now points to it.
3. **Harness error, SEND-06 (superseded).** The recovery check compared the stub count with a baseline taken before
   the intended SEND-07 send to the T12-resolved customer, so it recorded FAIL. The stub log shows no send request between
   that send (03:30:49Z) and the next intended one (03:33:34Z), while recovery ran at 03:32:31Z. The
   script now takes the baseline just before the wait, and the corrected line is recorded.
4. The double-click count comes from a fetch wrapper installed in the page for the test (inspection only). The
   concurrent sends used `curl` with one BFF session; their status tally in the recorded note is concatenated without
   separators (fixed in the script). The verdicts rest on the attempt and stub counts. Those `curl` calls ran through
   the protected curl wrapper (loaded by the shared #333 helpers), so no session value reached a command line. The
   script now also loads the wrapper itself, as the evidence-script rule in `scripts/tests/bff-login-test.sh` requires.
   After code review the scripts were hardened again, without changing the recorded lines:
   - fixture numbers come from the database, so reruns on the same stack never reuse a number;
   - the mapping-disabled and unproven-integration checks require their exact codes;
   - the T12 check asserts the HTTP status;
   - the repeated-key check asserts that no idempotency record is kept for a refusal;
   - the W2 crash restart runs in `w2-restart335.sh`, which holds the window lock and, right before restarting with
     the global switch ON, requires the W2 token this run opened and its live watchdog. A window that was closed
     (manually or by the watchdog), replaced or left without a watchdog is never re-enabled; the probe aborts.
     `w2-restart-test335.sh` checks this offline with the real `window-close334.sh` against a fake stack (12 checks):
     active window, close first, close racing the restart, watchdog close, replaced token, dead watchdog, and a
     negative control showing that an unguarded restart breaks the invariant.
5. Every backend restart ends BFF sessions; the browser re-entered through the surviving Keycloak SSO session.
6. Cleanup (`final335.sh`): every still-open suite message was cancelled, the 21 `QA335` customers were archived,
   and the stub was reset to `accept`. `GENERAL_FOLLOW_UP` had already been restored to `qa333_general_follow_up` by
   `w1-integration335.sh`. Final database
   counts:
   - messages: 26 `CANCELLED` (15 from #333/#334), 2 `FAILED`, 1 `REJECTED`, 11 `SENT`;
   - attempts: 11 `ACCEPTED`, 2 `OUTCOME_UNKNOWN`;
   - tenant A: enabled, proven, outbound off.

## Reproducing

From the #333 stack (see its report), with the global switch off:

1. `fixtures335.sh w1`, then `send01-off335.sh` and the SEND-01 browser steps.
2. Open W1: `OUTD=~/qa335/out STACK333=…/stack335.sh WINDOW_TTL=1800 window-open334.sh W1`.
3. `w1-gates335.sh` and `w1-ineligible335.sh`.
4. Browser steps for SEND-04, the SEND-05 double click (after `stub_delay 1500`) and the SEND-06 hang (after
   `stub_mode send hang`).
5. `w1-dup335.sh`, `s6-unknown335.sh` and `w1-integration335.sh` (last: it rotates the credential).
6. Close W1: `window-close334.sh W1`.
7. `fixtures335.sh w2`.
8. In one shell, `export OUTD STACK333 QA335_MIN_INTERVAL=3m QA335_CLAIM_TIMEOUT=2m QA335_RECOVERY_INTERVAL=15s
   WINDOW_TTL=1200`, open W2, run `w2-335.sh`, then close W2. The script stops if the overrides are not in effect, and
   its crash restart passes the W2 values explicitly and checks they survived (the recorded run exported them in the
   same shell: recovery ran 2 minutes after the claim, and the second SEND-07 message was sent after 186 s).
9. `final335.sh`. Offline, `w2-restart-test335.sh` checks the W2 crash-restart guard.

The scripts are in [`issue-335-evidence/scripts/`](issue-335-evidence/scripts/), and the recorded run is
[`issue-335-evidence/outputs.txt`](issue-335-evidence/outputs.txt).

## Limitations

- Controlled provider mode: `SENT` means the stub accepted the call. Delivery, callbacks and webhook reconciliation are
  suite #336; real Meta is suite #339.
- The crash was simulated by killing the backend container during a provider call that never answers. A crash after a
  real acceptance looks the same to Dokene (unknown outcome). Only a webhook could later match the provider id, and
  that is suite #336.
- The browser view of the crash case was not opened; its state equals the hang case, which was checked in the browser.
- The 24 h default interval was confirmed by configuration, not by waiting 24 hours. Its enforcement path was exercised
  with the 3 m override.
- Concurrency ran on one local machine against one backend instance; multi-instance races are out of scope here.
