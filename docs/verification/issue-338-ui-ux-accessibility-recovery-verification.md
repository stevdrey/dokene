# Issue #338: Phase 3 manual QA, responsive layouts, accessibility and operator recovery

Suite of the Phase 3 manual QA epic [#332](https://github.com/stevdrey/dokene/issues/332). Cases P3-MQA-UX-01 to
UX-07.

**Verdict: FAIL (scoped)** for candidate `42eec817fe3f305d4245852755e390c9a0693276` (`main`, 2026-11-17), on the local
stack with the loopback Graph stub. 4 PASS (UX-01, UX-02, UX-04, UX-07), 3 FAIL (UX-03, UX-05, UX-06), and the
screen-reader part of UX-03 BLOCKED. Four new defects, D1 to D4 ([#415](https://github.com/stevdrey/dokene/issues/415) to [#418](https://github.com/stevdrey/dokene/issues/418)): keyboard focus, word breaks in labels, the
multiline draft body, and misleading copy. None of them is a safety, tenant or security problem. Every recovery path
kept the server state correct: no lost response, offline period, ambiguous 500, parallel change, ended session, revoked
member, rate limit or double activation created a second approval or a second provider request. The failures are about
comprehension and accessibility. Fix and retest are pending.

## Environment

| Item | Value |
| --- | --- |
| Candidate | `42eec81`. It differs from the #333 candidate `3807963` only under `docs/` (`git diff --stat 3807963 42eec81 -- . ':!docs'` is empty), so the #333 images were reused |
| Stack | The #333 compose project `dokene-qa333` (disposable volume), worktree moved to the candidate; dev frontend on `http://localhost:5173` |
| Browsers | **Chromium 156.0.8078.4** (Playwright build) and **Firefox 160.0** (the system Firefox, driven over WebDriver BiDi), both headless, locale `es-CL`. Driven by Playwright `playwright-core` 1.64.1, loaded from a sibling checkout's `node_modules` (not a project dependency; nothing was downloaded) |
| AI provider | `fake` (deterministic drafts that echo the customer name) |
| WhatsApp provider | Loopback Graph API stub with synthetic canary tokens |
| Switches | Global outbound **off**, except window **W1** (user-approved, stub only, watchdog 25 min): open 18:27:45Z, closed and verified 18:33:08Z. Seven sends, one per W1 message |
| Users | OWNER `testuser` (tenant A *QA Café Norte*, ADMIN in tenant B *QA Café Sur*), ADMIN `testadmin`, OPERATOR `testoperator`. The extra Keycloak users were dropped again by a Keycloak restart and recreated (`fixtures337.sh`) |
| Fixtures | 47 disposable `QA338 …` customers in tenant A (WhatsApp consent, due today), all archived at the end |

Evidence kinds are kept apart. *Browser* means a real Chromium or Firefox session against the real stack, signed in
through the real Keycloak form and driven by the scripts below (clicks, key presses, viewport and zoom changes). It is
read back as page text, the accessibility snapshot, DOM measurements and screenshots, which were inspected by eye.
*Injected* means a network fault made in the browser with Playwright routing or offline mode: a delay, a dropped or
replaced response, or a 429 the server did not send. Every injected case says so, and the server state before and after
comes from the database. *API/DB* means calls through the BFF session by the bash helpers, or read-only SQL. *Stub* is
the controlled provider's request log. Nothing here comes from unit or integration test fixtures, and nothing comes from
real Meta.

## Results

| Case | Result | Evidence | Notes |
| --- | --- | --- | --- |
| UX-01 Viewports | **PASS** | Browser, both browsers, eight screens at 1440×900, 1024×768, 768×1024, 390×844 and 320×568 (80 screen checks). The screens: follow-up queue, review of a pending message, approval confirmation, review of an approved message, a real refused send (`OUTBOUND_DISABLED_GLOBALLY`), message history, integration and template status, and the integration edit form. Results: no page-level horizontal overflow, no element past the viewport edge, no clipped control or text, no unreachable control (each one scrolled into view and hit-tested), every expected action present. The refusal alert was inside the viewport in all 14 views | **D2**: two labels break inside a word. The history row's *Actualizar* button reads "Actualiz/ar" at 768 px and wider; *Posponer* and *Descartar* read "Pospone/r" and "Descarta/r" at 320 px. The labels stay readable and clickable |
| UX-02 Browsers, zoom, reflow | **PASS** | Browser: the same matrix at 200 % zoom (1440×900 window, 720×450 CSS px at DPR 2) and 400 % zoom (1280×1024 window, 320×256 CSS px at DPR 4, the WCAG 1.4.10 reflow reference) in both browsers: no overflow, clipping or unreachable control. Core paths run in both browsers: UX-03 to UX-07 below | Firefox and Chromium rendered identically, apart from the classic scrollbar width (Firefox reports 378 of 390 px). Firefox restores the page from its back/forward cache on *Forward*. Two driver limits, not product behaviour: BiDi has no `route.fetch()`, so the two "response lost after commit" cases ran in Chromium only, and `fill()`/`insertText()` do not reach React state in Firefox, so the Firefox scripts type key by key |
| UX-03 Keyboard and assistive technology | **FAIL** (keyboard), **BLOCKED** (screen reader) | Browser, both browsers, keyboard only from the app's login button to a sent confirmation (UX-03 lines in `outputs.txt`). Passed: Keycloak sign-in, logical tab order (workspace, navigation, sign-out, filters, search, queue), AI recommendation, draft hand-off, submit, approve and send confirmations. Each confirmation group takes focus, and Escape closes it and returns focus to its trigger (*Enviar a aprobación*, *Aprobar*, *Enviar*). A refused send is announced as an alert while focus stays on the confirm button. The snooze dialog traps Tab and Shift+Tab, Escape closes it, and focus returns to *Posponer*. The workspace selector opens and closes from the keyboard. Supporting audit (Chromium, five screens): `lang=es`, 0 unnamed controls, 0 WCAG AA contrast failures in about 1,550 text nodes, 0 targets under 24 px, alerts and live regions present | **D1**: (a) the queue search box has no visible focus indicator (inline `outline: none`, no replacement; the focused and unfocused screenshots are identical), WCAG 2.4.7; (b) after *Confirmar envío a aprobación* focus drops to `<body>`, WCAG 2.4.3; the result itself is announced in a `role=status`; (c) no skip link, WCAG 2.4.1. **Screen reader: BLOCKED.** No NVDA or Orca session was run. It needs a headed browser on the operator's desktop and its audio. The audit above is not a substitute, as in #122 |
| UX-04 Status and error copy | **PASS** | Browser, both browsers. Every state was produced for real: submitted, approved, provider accepted (stub accept), delivered and read (signed synthetic callbacks), failed (stub 401, `AUTHENTICATION`) and uncertain (stub `paused`: `SENDING`, unknown outcome). History: *Aún no enviado · Pendiente de aprobación*, *Aún no enviado · Aprobado, sin enviar*, *Aceptado por el proveedor · Aún sin confirmación de entrega*, *Entregado · Entregado al teléfono del cliente*, *Leído · El cliente abrió el mensaje*, *Fallido · No se entregó al cliente.* with guidance, *Resultado sin confirmar · El proveedor no confirmó el envío. No lo reenvíes…*. The review shows the matching status, attempt outcome (*Aceptado*, *Falló definitivamente*, *Resultado desconocido*) and notice. *Enviar* is offered only for the approved message. No state claims delivery before a callback, and no failed or uncertain state shows success | Observation O1: the review badge of a provider-accepted message reads *Enviado*, while the history says *Aceptado por el proveedor*; it is true but less precise. **D4 (part)**: the AI panel and the workbench footer still say "Dokene no ha enviado nada. El envío es manual por tu canal habitual" next to a message Dokene sent |
| UX-05 Latency, connectivity, errors, retry | **FAIL** | Browser and API/DB. Results: **latency** (injected): a slow read shows *Cargando mensaje…* in an `aria-busy` region; a slow approval relabels its button *Cargando...* (`aria-disabled`), and three more activations sent no second request. **Lost response after commit** (injected, Chromium): the server approved, the browser saw a reset; the UI said *La acción pudo haberse completado. Revisa el estado actual.*, re-read the state and showed *Aprobado* with *Enviar*: 1 approval. **Offline** (browser offline mode): *No se pudo confirmar la acción.*, server untouched; back online, *Reintentar* approved exactly once. **Ambiguous 500** (injected after commit): treated as uncertain, 1 approval. **409**: an ADMIN approved in parallel; the stale approve said *Este mensaje cambió: ahora está Aprobado.*, 1 approval. **401**: app logout in a second tab, then approve: *Tu sesión ya no está activa…*, message unchanged. **403**: OPERATOR membership revoked while the review was open: *No tienes permiso para esta acción.*, unchanged, then reactivated. **429 real**: 21 recommendation calls hit the limiter, and the assistant said *Hay demasiadas solicitudes al asistente IA. Espera un momento…*. **429 send** (injected): *Se alcanzó el límite de envíos. Espera 29 s.* with a countdown and locked button, nothing sent. **Send ambiguity (W1)**: a send whose response was lost after the provider call made 1 attempt and 1 stub request, the UI claimed neither outcome, and no retry or *Enviar* was offered afterwards. Double activation during a 3 s provider delay: 1 request, 1 attempt, 1 stub call. Resending the uncertain message over HTTP: 409 `INVALID_TRANSITION`, no provider request. **Invalid credentials** (tenant B, both browsers): a number the credential does not own gives *No se pudo comprobar la propiedad del número con la referencia configurada…* (clear); the original values were restored and proven again | **D4**: a credential reference that resolves to nothing is reported as *El proveedor no está disponible en este momento. Intenta más tarde.* The operator is told to wait for an outage that does not exist, instead of being pointed at the reference. All safety checks passed; the failure is comprehension only |
| UX-06 Long, accented, emoji, multiline, HTML-like text | **FAIL** | Browser, both browsers. The customer names with accents, emoji, `<b>…</b>`, `&` and typographic quotes render literally in the queue, detail and AI draft. A 720-code-point body (ñ, accents, emoji and a ZWJ sequence, quotes, `<b>`, `<script>`, `<img onerror>`, `&amp;`, `{{7*7}}`) is held exactly in the editor. The counter counts code points (720/1000). *Copiar borrador* puts exactly that text on the clipboard (Chromium). The compose shows it verbatim. Nothing was executed or inserted (no flag, `b`, `img` or `script` element, no dialog), and nothing overflows at 390 or 320 px. As a single line the body is submitted, and the review renders every character | **D3**: a multiline body is refused at submit with the generic *El texto del mensaje no pasó la validación.* (`409 BODY_REJECTED`). Over HTTP the rule is newlines, tabs or long runs of spaces (Meta's template-parameter rules); accents, emoji, HTML-like text and length are accepted. The draft editor accepts Enter, nothing warns before submit, and the refusal does not say what to change. A multiline AI draft cannot reach approval without guesswork |
| UX-07 Stale responses and navigation | **PASS** | Browser, both browsers (delays injected). A slow history read for customer 1, then customer 2 opened: customer 2's view and messages only. A slow tenant A read, then a switch to tenant B: no tenant A name, message or text in B. An approval held 3 s on the response side while the operator opened another customer (Chromium): no foreign confirmation, 1 approval, and the first customer shows *Aprobado, sin enviar* on return. A viewport change to 390 px during an approval: 1 approval, result shown in the narrow layout. Back and forward land on a consistent queue with no stale data. Logout and relogin with an edited AI draft: a clear signed-out page, and on return the assistant starts over (*Obtener recomendación*), with no half-restored state | Observations: O2, the app keeps no in-app history (Back returns to the OIDC callback entry, which reloads the queue; Firefox restores from bfcache). O3, logging out discards an edited draft without a warning; recovery is understandable but silent |

Totals in `issue-338-evidence/outputs.txt`: 129 PASS, 14 FAIL (all mapped to D1, D3 and D4), 1 HARNESS-ERROR (setup
note 4), and 112 viewport-sweep rows.

## Defects and observations

Searched first: open and closed issues for focus, skip link, outline, word break, multiline, line break,
`BODY_REJECTED`, the AI disclaimer and credential copy. #197 and #277 (focus losses) are closed and are different
paths; nothing matched.

| Id | Defect | Severity | Issue |
| --- | --- | --- | --- |
| D1 | Keyboard focus gaps: the queue search box has no focus indicator; focus is lost after submit to approval; there is no skip link (WCAG 2.4.7, 2.4.3, 2.4.1) | Medium | [#415](https://github.com/stevdrey/dokene/issues/415) |
| D2 | Button labels wrap inside a word: the history row's *Actualizar* at ≥768 px, *Posponer* and *Descartar* at 320 px | Low | [#416](https://github.com/stevdrey/dokene/issues/416) |
| D3 | A multiline (or tab or space-run) draft body is accepted by the editor but refused at submit with a generic sentence; no warning before and no guidance after | Medium | [#417](https://github.com/stevdrey/dokene/issues/417) |
| D4 | Misleading copy: an unresolvable credential reference reads as a temporary provider outage; the AI panel and footer still say Dokene sends nothing and sending is manual | Medium | [#418](https://github.com/stevdrey/dokene/issues/418) |

| Id | Observation | Classification |
| --- | --- | --- |
| O1 | The review badge says *Enviado* for `SENT`; the history says *Aceptado por el proveedor · Aún sin confirmación de entrega* | Wording consistency; not a false claim |
| O2 | No in-app browser history; Back and Forward reload or restore the queue | By design of the current shell; consistent state |
| O3 | Logout discards an edited AI draft without a prompt | Understandable; optional improvement |
| O4 | Queue cards expose their whole content as the accessible name; Configuración has no `h1`, and the workbench heading order is h1, h3…, h2 (as noted in #122) | Screen-reader verbosity; to confirm in the BLOCKED session |

## Setup notes

1. **Harness errors in UX-01 (superseded).** The first sweep flagged visually hidden labels as clipped text and treated
   controls taller than the 256 px reflow viewport as unreachable. The probes were corrected (1 px boxes skipped; an
   oversized control counts as reachable when its centre is on screen and hit-testable) and the affected viewport was
   rerun in both browsers. The word-break probe ignores breaks at hyphens and the synthetic unspaced token
   `<b>Negrita</b>`.
2. **Harness errors in UX-03 to UX-07 (superseded).** Several first runs failed on the harness, not the product.
   Accessible names were truncated before matching. Icon ligature text was part of names (*schedule Posponer*). A busy
   button renamed itself *Cargando...* mid-check. The UI recovered without needing *Reintentar*. A sent message's review
   has no template preview. Sent customers leave the due queue (rescheduled with `due_today`). A section was read while
   still loading. A warning regex matched the test body's own text. Each script was fixed and rerun on fresh fixtures;
   only the final runs are in `outputs.txt`, apart from four Chromium UX-05 lines (latency, 403) recorded on the console
   of the first runs.
3. **Client-side delay is not network latency.** In UX-07 a delay injected *before* the request left the browser let
   the app abort the approval when the operator navigated away, so it never reached the server. That models no real
   network. The case was rerun with the delay on the response side (`route.fetch()`, Chromium), where the server
   receives the command at once.
4. **HARNESS-ERROR, W1 accepted send.** The check read only the review section, while the *Envío procesado. Estado
   actual: …* sentence sits in a live region outside it; the script's wait for that sentence on the page had succeeded.
   The state copy of that message was checked in UX-04 (both browsers).
5. Window W1 used the #334 window scripts with this suite's `OUTD` and the #335 stack wrapper. Seven sends reached the
   stub (22 → 29): three from the browser (accepted, response lost, double activation) and four over HTTP (delivered,
   read, failed, uncertain). The uncertain message was reconciled by a delivered callback before closing; no resend.
6. Tenant B's integration was edited twice through the UI (unresolvable reference, then restored) and proven again
   against the stub. It ended on its own number and reference.
7. Cleanup (`final338.sh`): 32 open suite messages cancelled, 47 `QA338` customers archived, global and tenant outbound
   off, stub send count 29. Final database counts (all suites on this stack): messages 104 `CANCELLED`, 4 `DELIVERED`,
   6 `FAILED`, 3 `READ`, 1 `REJECTED`, 16 `SENT`; 29 attempts.
8. One crash trace of the Firefox driver printed request headers, including a local session cookie and CSRF token, to
   the terminal. Nothing was written to a file. The session was a disposable local QA session and ended with the
   window restart.

## Reproducing

From the #333 stack (see its report), with the global switch off, `OUTD=~/qa338/out`, and the passwords supplied
through the environment (`QA_PW`, `QA_PW_OP` from `scripts/lib/test-passwords.sh`):

1. `OUTD=~/qa338/out fixtures337.sh` (users), then `fixtures338.sh` and `fixtures338-ux05.sh` (optionally a second set
   with `SUF=" FF" IDSUF=F`).
2. `node ux01-338.mjs chromium|firefox`, `node ux03-338.mjs <browser> <customer>`, `node ux03b-338.mjs`,
   `node ux05-338.mjs <browser>` (`SKIP_FETCH=1` for Firefox), `node ux05b-338.mjs <browser>`,
   `node ux06-338.mjs <browser> <customer>` (`BODY_MODE=single` for the single-line variant),
   `node ux07-338.mjs <browser> [suffix]`.
3. With the user's approval only: `fixtures338-w1.sh`, open the window (`window-open334.sh W1` with `OUTD`,
   `RESULTS`, `STACK333=…/stack335.sh`), `node ux04-338.mjs send`, `w1-338.sh sends`, `w1-338.sh callbacks`,
   `node ux04-338.mjs copy <browser>` (after `due_today` on the sent customers), `w1-338.sh reconcile`,
   `window-close334.sh W1`.
4. `final338.sh`, then `python3 -I build-outputs338.py ~/qa338/out outputs.txt`.

The scripts are in [`issue-338-evidence/scripts/`](issue-338-evidence/scripts/), and the recorded run is
[`issue-338-evidence/outputs.txt`](issue-338-evidence/outputs.txt). Screenshots stayed local (`~/qa338/shots`) because
they show synthetic data only and add no evidence beyond the recorded measurements.

## Limitations

- Headless browsers on one Linux host; no Safari, Edge, physical phone or touch input. The 200 % and 400 % zoom runs
  emulate zoom with the device pixel ratio and a smaller CSS viewport.
- No screen-reader session (UX-03 BLOCKED); the accessibility audit is supporting evidence only.
- Network faults were injected in the browser; real proxies or mobile networks were not used. The two after-commit
  cases ran in Chromium only.
- The tenant send limiter (`RATE_LIMITED` on sends) was not reached for real; its UI was checked with an injected 429.
- One local stack, the dev frontend at port 5173, the fake AI provider and the stub instead of Meta.
