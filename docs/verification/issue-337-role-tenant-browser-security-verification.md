# Issue #337: Phase 3 manual QA, role, tenant and browser security boundaries

Suite of the Phase 3 manual QA epic [#332](https://github.com/stevdrey/dokene/issues/332). Cases P3-MQA-SEC-01 to
SEC-07.

**Verdict: PASS (scoped)** for candidate `21dd085972bf0e08e8aee5ab7542bdd7f074ab49` (`main`, 2026-11-17), on the local
stack with the loopback Graph stub. 7 of 7 cases PASS, 0 FAIL, 0 BLOCKED, no new defects. Every unauthorized,
cross-tenant, CSRF-less, ended-session, revoked-member, cross-origin or tampered request was refused. None of them changed
a message, customer, audit trail (apart from the expected denial row), AI counter or provider counter. The stub send
count stayed at 22 from the first probe to the cleanup. The global outbound switch was off for the whole suite, and no
window was opened.

## Environment

| Item | Value |
| --- | --- |
| Candidate | `21dd085`. It differs from the #333 candidate `3807963` only under `docs/` (`git diff --stat 3807963 21dd085 -- . ':!docs'` is empty), so the #333 images were reused |
| Stack | The #333 compose project `dokene-qa333` (disposable volume), worktree moved to the candidate |
| Browser | Claude desktop built-in browser (Chromium) on `http://localhost:5173`, signed in as the OWNER `testuser` on the local Keycloak |
| AI provider | `fake` (deterministic drafts; it echoes the customer name, which carries the XSS canary) |
| WhatsApp provider | Loopback Graph API stub with synthetic canary tokens. Only template and phone-list reads reached it (tenant B setup and proofs); no send |
| Switches | Global and tenant outbound **off** for the whole suite |
| Fixtures | Tenant A *QA Café Norte*: OWNER `testuser`, ADMIN `testadmin`, OPERATOR `testoperator`, VIEWER `testviewer`; `testnone` has no membership. Tenant B *QA Café Sur*: ADMIN `testuser`, OPERATOR `testoperator2`. Disposable customers `QA337 Roles`, `QA337 Norte IDOR`, `QA337 Canario …` (A) and `QA337 Sur IDOR` (B), each with WhatsApp consent and due today; one pending message in B |

Evidence kinds are kept apart. *API/DB* means an authenticated HTTP call through the real BFF session (cookie, CSRF
token, tenant header) or a read-only SQL check, made by the scripts below. *Browser* means a person-driven action in the
real UI or a page on a foreign origin, read back as page text, DOM queries, storage dumps and the network panel.
*Webhook* means a raw `curl` call to the public route with no cookie jar. *Stub* is the controlled-provider request log.
Nothing here comes from unit or integration test fixtures, and nothing comes from real Meta.

Each refusal check records a state snapshot before and after: message, event, attempt and customer counts, non-denial
audit rows, the stub's request log and, where relevant, the target row's status, version and body hash. A check passes
only if the snapshot is identical.

## Results

| Case | Result | API / DB / webhook | Browser | Notes |
| --- | --- | --- | --- | --- |
| SEC-01 Role matrix by direct HTTP | **PASS** | VIEWER: submit, AI draft, approve, cancel, send, template write, template refresh and all four integration manage routes → 403, exactly one `AUTHORIZATION_DENIED` row naming the missing permission, nothing else written, no AI invocation. OPERATOR: T12 resolution and the four integration manage routes → 403 `INTEGRATION_MANAGE`. Non-member → 403 `TENANT_READ/NO_TENANT_CONTEXT`. Authorized roles pass the check and stop at the next gate, writing nothing: send (OWNER, ADMIN, OPERATOR) → 409 `OUTBOUND_DISABLED_GLOBALLY`; resolution of an approved message (OWNER, ADMIN) → 409 `INVALID_TRANSITION`; template write/refresh (OWNER, ADMIN, OPERATOR) and tenant switch/ownership proof (OWNER, ADMIN) with a stale `If-Match` → 409 `STALE_VERSION`. **OPERATOR self-approval** of its own message → 201 `APPROVED`, as the matrix allows (no second approver, #141). ADMIN approves the OWNER's message; OPERATOR, ADMIN and OWNER cancel | – | Probed only over HTTP, not through hidden UI buttons. The integration ETag and `outboundEnabled` were unchanged at the end |
| SEC-02 Cross-tenant substitution (IDOR) | **PASS** | The same person (OWNER of A, ADMIN of B) with the B header on A's message: read, events, approve (with A's ETag and revision), cancel, send, resolve → 404 `NOT_FOUND`. Reverse direction (A header, B message): read and approve → 404. A's customer from B: read, update, purchases, contact policy, consent revoke, follow-up policy, AI draft, submit → 404. Submitting on B's customer with **A's contact id** → 404. No body named a foreign id, name or phone, and no row of A or B changed. An A-only OPERATOR with the B header, and a B-only OPERATOR with the A header → 403. Searches in B (name, A's phone with region) list only B customers; positive controls in A find A's. B's message list (also filtered by A's customer id) and follow-up queue hold no A row. A template write in B with A's version as `If-Match` → 409 `STALE_VERSION` (versions are per tenant). Integration: tenant B could **store** A's phone number id and A's credential reference name (by design, see below); B's ownership proof of A's number → 400 `INVALID_INPUT`, A keeps the only verified mapping; a signed callback for A's number lands in tenant A only; B's proof with A's reference → 503 `PROVIDER_UNAVAILABLE`, **no provider request** and no stub request carrying A's token. B was restored and proved again. No B audit row references an A object. Forced RLS and the runtime role's posture match #149 P3-RLS (SEC-07) | Workspace switched to *QA Café Sur*: no tenant A name, canary, customer or message id in the page text or HTML | The integration claim behaviour is ADR 0024, #210 addendum: `PUT` accepts any well-formed unverified claim and is no oracle; references resolve only in the caller tenant's namespace (#142 addendum); routing and sends use verified mappings only. Five first-run lines were harness errors (setup note 2) |
| SEC-03 CSRF, ended session, revoked member, cross origin | **PASS** | Submit, AI draft, approve, cancel, send, consent change, template write and tenant switch, each with otherwise valid preconditions: no CSRF token, another session's token, a forged token, and a foreign `Origin`/`Sec-Fetch-Site: cross-site` without a token → 403, no state, AI or stub change (32 checks). A CORS preflight from a foreign origin gets no `Access-Control-Allow-Origin`. Control: the session's own token approves. After an app logout (`POST /logout` → 204) and after a Keycloak back-channel logout, the old cookie with its old token → 401 on approve, cancel and send. A live OPERATOR session in B after the ADMIN revoked its membership → 403 on approve, cancel and read, without data, nothing written; the membership was reactivated and accepted afterwards | A probe page on `http://localhost:18337` (same site, so the session cookie is attached) and on `http://127.0.0.1:18337` (cross site): a `no-cors` text/plain POST and a form POST to cancel the approved message → 403; a credentialed send → preflight 403, blocked; a credentialed read of `/api/session` → blocked by CORS. The message stayed `APPROVED`, version unchanged | The idle timeout (30 min) and the 8 h absolute lifetime were not waited out; the ended-session cases use logout and IdP back-channel logout |
| SEC-04 Tampering with approved body, recipient and preconditions | **PASS** | On an approved message: a send carrying `body`/`recipientPhone`, a send with `{}`, a re-approval or cancellation carrying `body` or `contactId` → 400 `INVALID_INPUT`. `PUT`, `PATCH`, `DELETE` on the message → 405 (no edit route). A valid re-approval → 409 `INVALID_TRANSITION`. Send with `If-Match` `"abc"`, weak, `*`, two values or missing, or without `Idempotency-Key` → 400; stale → 409 `STALE_VERSION`. Submit with an extra `recipientPhone` or `tenantId` member → 400. In every case the approved row's status, version, body hash and recipient were unchanged. **Idempotency keys are tenant scoped:** B's submit key reused in A created a new A message (not B's replay); A's approval key reused in B decided B's own message without A's response; the same key on another A message → 409 `IDEMPOTENCY_KEY_REUSED`, that message unchanged; a replay with the key returned the stored result and no second approval row | – | Recipient changes made through the customer (phone edits after approval) invalidate approvals; that path was covered by #334 |
| SEC-05 XSS canary in customer data and AI drafts | **PASS** | The canary `QA337-XSS <img src=x onerror=…> "><svg onload=…> {{7*7}}` is stored verbatim in the customer name and notes and in the submitted AI-draft message (`AI_DRAFT`). The draft and message JSON carry it as an escaped string; `renderedBody` keeps `{{7*7}}` literal (a placeholder inside a value is not expanded). Container logs (backend, frontend, OIDC bridge, last 3 h, about 2,400 lines) contain no canary, name, note, phone, secret canary, session or CSRF value. Audit rows contain no canary text | Queue card, customer detail, AI recommendation, editable draft, review screen, confirmation, the customer preview *Lo que recibirá el cliente*, the history row and its timeline all show the canary as text. The page flag never set, no `img src=x`, no `svg`, no `on*` attribute, `{{7*7}}` never evaluated. `localStorage` empty; `sessionStorage` holds only the active tenant id; `document.cookie` exposes nothing (HttpOnly). No `QA333-CANARY` secret in any page or response read | – |
| SEC-06 Webhook exemption scope, #266 and #265 | **PASS** | `GET /webhooks/whatsapp` with the verify token → 200 echoing the challenge, no cookie; wrong token → 403, no echo, no cookie. A signed POST with no session or CSRF → 200, no cookie (unknown number: nothing stored). Siblings `/webhooks`, `/webhooks/`, `/webhooks/other`, `/webhooks/whatsapp/`, `/webhooks/whatsapp/status`, `/webhooks/WhatsApp`, `/webhooks/whatsapp2` → 403; `;x=1` and an encoded `..%2F` → 400; `/webhooks/%2e%2e/api/session` → 401. None echoed the challenge, set a cookie or stored a callback, for GET or signed POST (**#266 holds**). `PUT`, `DELETE`, `PATCH` on the callback → 405, no cookie. A signed but session-less POST to an `/api` route → 401, nothing written. A VIEWER cancel of a pending and of an approved message → 403 with **exactly one** denial row naming `MESSAGE_DRAFT` (**#265 holds**) | – | Direct to the backend port; the dev frontend proxies only `/api` and `/oauth2` |
| SEC-07 Comparison with #149 and #305 | **PASS** | Forced RLS on the nine tenant tables these probes touched; the runtime role is neither superuser nor `BYPASSRLS` (the #149 P3-RLS posture; the forged-capability cases were not repeated). Comparison in the next section | – | No release-impacting problem found |

Totals in `issue-337-evidence/outputs.txt`: 197 PASS lines, 7 NOTE lines, 0 FAIL, and 48 `HARNESS-ERROR` lines (all
superseded; setup notes 1 to 3).

## Comparison with #149 and #305 (SEC-07)

| Item | Status on this candidate | Classification |
| --- | --- | --- |
| [#265](https://github.com/stevdrey/dokene/issues/265) one any-of denial wrote two audit rows | One row per denied cancel (pending and approved) | Fixed, regression check passes |
| [#266](https://github.com/stevdrey/dokene/issues/266) `/webhooks` siblings set `JSESSIONID` | No sibling path or method sets a cookie | Fixed, regression check passes |
| [#307](https://github.com/stevdrey/dokene/issues/307) M3 IdP logout and absolute lifetime | A Keycloak back-channel logout ends the BFF session (401) | Closed finding, behaviour holds; absolute lifetime not waited out |
| [#310](https://github.com/stevdrey/dokene/issues/310) L1 denial audit amplification (open) | Every denied request still writes one unbounded `AUTHORIZATION_DENIED` row (this stack went from 68 to 112 denial rows during the suite) | Known open hardening in #305, not a fresh regression |
| ADR 0024 (#210, #142) unverified integration claims | B can store A's phone number id or A's reference name; proof, routing and sending stay contained | By design (no oracle), not a finding |
| [#312](https://github.com/stevdrey/dokene/issues/312) L3 self-provisioning, [#318](https://github.com/stevdrey/dokene/issues/318) L7 redirect origin (open) | Not exercised by this suite's routes | Open, tracked in #305 |
| #149 accepted constraints (metrics half of P3-SEC-07, P3-SEC-11) | Not re-driven | Unchanged deployment limitations |

## Defects and observations

No new defect. Searched first: open `phase-3` issues (only the #332 suites) and the open #305 security issues; none is
contradicted by these results and no new behaviour needs one.

| Id | Observation | Classification |
| --- | --- | --- |
| O1 | A tenant `INTEGRATION_MANAGE` user can store another tenant's phone number id or credential reference *name* in its own integration. Nothing resolves or routes through it: the proof fails, no provider call is made with the other tenant's token, and callbacks keep going to the verified owner | By design (ADR 0024, #210 and #142 addenda). Recorded so later suites do not report it as an IDOR |
| O2 | Approval, refresh and integration routes check the role before the precondition, so an authorized role with a stale `If-Match` gets 409 and an unauthorized one 403. A VIEWER learns nothing about versions | Expected order of checks |
| O3 | A customer name containing `{{…}}` is passed into the template parameter unchanged and never expanded. Meta's own parameter rules (#339) decide whether such a value is accepted at send time | Not a security issue; noted for #339 |
| O4 | `/webhooks/%2e%2e/api/session` answers 401: the server normalizes the path to `/api/session` and applies the `/api` chain, not the webhook exemption | Correct: no exemption, no cookie |

## Setup notes

1. **Harness error, SEC-01 (superseded).** The first verdict helper (`pass`) evaluated only the first condition of each
   `&&` chain, so the non-member check passed although its denial row names `TENANT_READ`, not the expected
   `MESSAGE_READ`. Every check was rewritten as one grouped condition, the expectation was corrected (a non-member has
   no tenant context, so the guard records `TENANT_READ/NO_TENANT_CONTEXT`), and SEC-01 was rerun in full. The 42
   first-run lines are kept at the end of `outputs.txt` as `HARNESS-ERROR`.
2. **Harness error, SEC-02 (superseded).** Three customer-search lines read `.items` while the list is `.customers`
   (vacuous counts; the phone search also lacked `region`), and two integration lines expected `PUT` to refuse another
   tenant's phone number id or reference name, which ADR 0024 explicitly allows. `sec02b-337.sh` redid the searches
   with positive controls and tested what the ADR requires: containment. The five lines are marked `HARNESS-ERROR`.
3. **Harness error, setup (superseded).** The first canary update sent the read shape of a phone (`e164`) and got 400;
   the corrected helper reran and passed.
4. The Keycloak restart in an earlier suite had dropped the extra synthetic users again. `fixtures337.sh` recreated
   `testadmin`, `testoperator2` and `testnone` and granted ADMIN (A) and OPERATOR (B) through the real invitation flow;
   old memberships of the dropped identities stay as inert rows.
5. Because of a helper bug (later fixed in `qa337.sh`) the four suite customers got phone numbers from the #336 test
   range (`+56983360016` to `0019`) and the tenant A ones the purchase description `QA336`. All numbers are synthetic
   CL test-range values.
6. The ownership proof of tenant B used the stub's phone-list read with B's canary token (two calls). Tenant B ended on
   its own number and reference, proven again.
7. In the recorded run, SEC-04 left its idempotency fixture `NX` approved on the canary customer, and one open
   message per customer would have blocked the SEC-05 submit. It was cancelled by hand (one `OWNER` cancellation,
   201 `CANCELLED`) before the browser step; that is the cancelled row in the canary history. After review,
   `sec04-337.sh` cancels and records it itself, and every suite script ends with `qa_finish`, so a recorded `FAIL`
   gives a nonzero exit status.
8. The browser signed in with the seeded local `testuser`. The foreign-origin page was a static file served by a
   loopback `python3 -m http.server`, stopped after the case.
9. Cleanup (`final337.sh`): the four open suite messages were cancelled (A: 3, B: 1) and the four `QA337` customers
   archived. End checks: global, tenant A and tenant B outbound off; A and B enabled and proven; stub send count 22.
   Final database counts (all suites on this stack): messages 69 `CANCELLED`, 2 `DELIVERED`, 5 `FAILED`, 2 `READ`,
   1 `REJECTED`, 13 `SENT`; 22 attempts.

## Reproducing

From the #333 stack (see its report), with the global switch off:

1. `fixtures337.sh`, then `fixtures337-objects.sh`.
2. `sec01-337.sh`, `sec02-337.sh`, `sec02b-337.sh`, `sec03-337.sh`, `sec04-337.sh`, `sec06-337.sh`.
3. Browser: sign in as `testuser`, open `QA337 Canario …` in the follow-up workbench, request a recommendation and a
   draft, review and submit it, open the history row; inspect the flag, DOM, storage and network; switch to *QA Café
   Sur*. Serve a page that posts to the approved message from `localhost:18337` and `127.0.0.1:18337`. Then
   `browser337.sh <canary message id>`.
4. `sec07-337.sh`, then `final337.sh`.

The scripts are in [`issue-337-evidence/scripts/`](issue-337-evidence/scripts/), and the recorded run is
[`issue-337-evidence/outputs.txt`](issue-337-evidence/outputs.txt).

## Limitations

- One local stack, one backend instance, the dev frontend at port 5173 (no production proxy). Webhook paths were probed
  on the backend port.
- The role matrix was driven over HTTP; the UI's hidden buttons per role were not re-inspected in this suite.
- Idle and absolute session expiry were not waited out; ended sessions were produced by app logout and IdP
  back-channel logout.
- The #149 forged and expired database capability cases (P3-RLS-02 to 04) were not repeated; only the RLS posture was
  rechecked.
- No real Meta and no real AI provider: the stub and the fake AI provider stand in for them.
