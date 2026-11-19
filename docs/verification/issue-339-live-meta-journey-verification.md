# Issue #339: Phase 3 manual QA, opt-in live Meta journey

Suite of the Phase 3 manual QA epic [#332](https://github.com/stevdrey/dokene/issues/332). Cases P3-MQA-LIVE-01 to
LIVE-06. Procedure: [phase-3-live-meta-procedure.md](phase-3-live-meta-procedure.md), followed step by step.

**Verdict: PASS (scoped)** for candidate `c53cea240f0532c185d9d1aaa43fd0241b8233f4` (`main`, 2026-11-18), on the local
live-override stack against **real Meta Graph `v23.0`**. 6 of 6 cases PASS, 0 FAIL, 0 BLOCKED, no new defects. The
recipient owner authorized exactly one send for this run. That one template send went through Dokene's normal API
(submit, revision-bound approval, send) with one provider attempt and was accepted (`SENT`). Real signed Meta status
callbacks then moved the same message to `DELIVERED` and `READ`, and the recipient confirmed receipt. The global
switch was on for at most about 37 seconds (see LIVE-04), and the window was verified closed (tenant off, global off,
interval `24h`) before any callback was read. Nothing was retried, and no other message or attempt was created.

## Environment

| Item | Value |
| --- | --- |
| Candidate | `c53cea24`. Outside `docs/` it is identical to `91915e2f`, the #338 certified tree (`git diff --stat 91915e2f c53cea24 -- . ':!docs'` is empty) |
| Stack | Compose project `dokene-qa-run` (the stack that holds the real Meta integration since #140), its worktree moved from `80c7ab7` to the candidate. Images `dokene-backend:qa339` and `dokene-frontend:qa339` built from that worktree. The existing database was migrated in place by the `migrate` job, V33 → V40 (7 migrations). The suite #333 stack was stopped for the run, with the operator's approval, and restarted afterwards |
| Live override | The operator's private override (local secret source, `secretRef`s, `DOKENE_MESSAGING_OUTBOUND_ENABLED=false`) plus `compose.qa339.yaml`. The Graph base URL is the built-in default `https://graph.facebook.com/v23.0`: neither the environment nor the override sets `dokene.integration.whatsapp.graph.base-url`, and the suite #333 Graph stub was stopped |
| Meta assets | Non-production: the Meta developer app (published since 2026-10-29, which is what lets real status callbacks through; precondition 1 of the procedure now says so, where it used to require development mode), its WhatsApp test sender and the approved Spanish test template. The access token is a short-lived test token, refreshed by the operator in the private files just before the run. No value is recorded here |
| Recipient | The single consenting test recipient registered in the Meta app; in Dokene WhatsApp consent `GRANTED`, no do-not-contact, last activity about 490 h earlier |
| Callback reachability | A temporary tunnel, started by the operator, in front of the loopback gateway that proxies only `/webhooks/whatsapp` and logs method, path, status and size. The operator set the app's Callback URL to it; Meta's verification `GET` was answered `200` |
| Switches | Both off before and after the run; open only inside the trapped window of LIVE-03 |
| Budget | One template send, authorized in chat by the recipient owner at 2026-11-18T17:14:27Z ("yes, one send"), plus the Graph reads of LIVE-02 |

Evidence kinds are kept apart. *Live provider* means a real call to Meta Graph or a real signed callback from Meta.
*API/DB* means an authenticated call through the real BFF session (cookie, CSRF token, tenant header) or a read-only
SQL check, made by the scripts below. *Gateway* is the tunnel gateway's access log. *Operator* is what the recipient owner
stated in chat. No browser was driven in this suite, and nothing here comes from unit or integration test fixtures or
from the Graph stub. Those offline proofs of the same path (`ApprovedSendJourneyIntegrationTest`, suites #335 and
#336) are separate and are not counted as live evidence.

## Results

| Case | Result | Evidence | Notes |
| --- | --- | --- | --- |
| LIVE-01 Preconditions before any external call | **PASS** | *Operator:* explicit authorization for one send on this candidate, recorded before any Meta call; recipient consent; refreshed token. *API/DB (local only, no Graph call):* integration enabled and verified, credential reference set, tenant outbound off; `GENERAL_FOLLOW_UP` mapped to the approved Spanish template, but `sendable: false` because its last approval check was 2026-10-29 (older than the 24 h maximum age); recipient `GRANTED`, not do-not-contact, last activity 490.6 h earlier, so the `24h` interval holds without lowering. Backend `outbound=false interval=24h`; the close-down path was run once on its own (only "off" steps, no window) and read back off, off, `24h`. *Gateway:* Meta's verification `GET` → `200` through the public tunnel | The stale template is expected on a stack idle since October, and LIVE-02 re-checks it through Graph. Exact SHA recorded above. The last-activity probe reads only the first 50 messages of this one customer record, not every record holding the phone number, so it is a local sanity check; the binding `24h` check is the server's own at send time, which accepted the LIVE-03 send |
| LIVE-02 Sender ownership and template approval on real Graph | **PASS** | *Live provider:* `POST /api/integrations/whatsapp/ownership-verifications` → `200`, `webhookVerified: true`. `PUT` + `POST /api/templates/GENERAL_FOLLOW_UP/approval-refresh` → `APPROVED`, `sendable: true`, language `es`, body present, checked 17:55:39Z. Effective Graph version `v23.0` (default base URL, no override) | Only redacted readiness was printed: no id, token, phone number or template body |
| LIVE-03 One authorized send through the normal API | **PASS** | `live339.sh` registered its `EXIT`/`INT`/`TERM`/`HUP` close-down handler, then opened the window: backend `outbound=true interval=24h` (17:56:11Z), tenant switch on. One submit → approve (with `templateRevision`) → send, each with its own fixed idempotency key (`dokene-live-339-20261118-es-*`). Outcome: **accepted**, `status: SENT`, `outcomeUnknown: false`, `failureCategory: null`, **1 attempt**, `sentAt` 17:56:14Z | Accepted by Meta is not delivered: delivery is LIVE-05 |
| LIVE-04 Close both switches, restore and read back | **PASS** | Close attempt 1 of at most 3, 17:56:31Z: tenant disable `0`, backend recreated with the global switch off `0`, healthy; read back tenant `{"tenantOutboundEnabled": false}` and backend `outbound=false interval=24h` → "closed (verified)". Exposure: the global switch came on with the window's backend recreate, which `live339.sh` started after the 17:55:54Z inbox baseline, and was verified off at 17:56:31Z, so at most about 37 s. The 17:56:11Z `window:` line is logged after the health wait, so it marks the end of startup, not when the switch came on (Docker no longer holds the container start events). The tenant switch was on from 17:56:11Z until the close after the 17:56:14Z send. The interval was never lowered. No retry: the outcome was not unknown, and the script never resends | Read back again at the end of the suite (LIVE-06) |
| LIVE-05 Signed `delivered`/`read` callbacks on the same message | **PASS** | *Live provider + DB:* three new inbox rows, all for the provider message id of the LIVE-03 message, each with 1 attempt: `sent` `IGNORED` (Dokene already had `SENT`), `delivered` `APPLIED` (Meta time 17:56:19Z), `read` `APPLIED` (17:59:01Z). *API:* the message reads `READ`, `deliveredAt` 17:56:19Z, `readAt` 17:59:01Z, still 1 attempt; timeline `SUBMITTED` → `APPROVED` → `SEND_REQUESTED` → `SEND_ATTEMPT_STARTED` → `SENT` → `DELIVERY_UPDATED` (`SENT`) → `DELIVERY_UPDATED` (`DELIVERED`) → `DELIVERY_UPDATED` (`READ`). *Operator:* the recipient confirmed receipt and opened the message | Inbox rows are written only for callbacks whose signature verified, so `APPLIED` is a signed callback. No `failed` callback was provoked and nothing was marked by hand. No backlog report arrived (the inbox went from 8 to 11 rows) |
| LIVE-06 Tunnel, callback configuration, sanitized record | **PASS** | Tunnel and gateway stopped at 17:59Z. Final read-back: tenant off, backend `outbound=false interval=24h`; since 2026-11-18 exactly 1 message, 1 send attempt and 3 inbox rows. *Gateway:* 1 `GET` `200`, 8 `POST` `502` and 3 `POST` `200` (below). The live stack was stopped and the suite #333 stack restarted, switches off | The Callback URL was **left pointing at the stopped tunnel**, at the operator's choice: it is unreachable, so Meta can deliver nothing to it. Before a later live run the operator sets it to that run's tunnel. Side effect not undone: `send339.py`, like the `send140*.py` helpers it copies, set the recipient customer's follow-up policy to no cadence with an explicit date of 2026-11-18 so the send was due. It was not restored, because that needs the live stack; the operator restores the cadence before the next live run |

## Callbacks during the window close

The first callbacks arrived while LIVE-04 was recreating the backend. The gateway answered `502` to 8 POSTs between
17:56:19Z and 17:56:23Z, of 1302, 1305 and 1307 bytes. The gateway logs no bodies, so it cannot show which of those
POSTs were redeliveries of the same report and which were distinct ones. POSTs of 1302 and 1307 bytes were accepted
with `200` at 17:56:36Z and 17:56:48Z, and the `sent` and `delivered` inbox rows match those times. No 1305-byte POST
got a `200` before the tunnel stopped, and what it carried is unknown. This matches the procedure (step 7, "keep the
tunnel up until the callbacks have arrived") and what #140 saw on `80c7ab7`. The inbox recorded every status of the
message (`sent`, `delivered`, `read`) exactly once, and none was applied twice. The `read` report came later, after
the recipient opened the message, and was accepted with `200` at 17:59:03Z.

## Comparison with the #140 live runs

| Item | #140 (`f4cb0ec`, `80c7ab7`) | #339 (`c53cea24`) |
| --- | --- | --- |
| Interval | Lowered to `1m` for back-to-back sends, with authorization | Not lowered: `24h` throughout |
| Callbacks | BLOCKED on `f4cb0ec` (app unpublished); `delivered` and backlog `read` on `80c7ab7` | `delivered` and `read` for the new send itself, no backlog |
| Stack | Pre-#308 compose (backend ran migrations) | `migrate` job, non-root PostgreSQL, per-user test passwords (#308, #314, #319); the live database was migrated in place to V40 |

## Limitations

- **Scoped.** This run proves one journey: one Spanish template send to one consenting test recipient through one
  non-production Meta app. It does not prove the English template, other locales, other senders, rate limits,
  production assets, or that no other defect exists.
- **No live `failed` callback**, by design of the issue (none was manufactured). The `failed` path and uncertain
  outcomes are covered offline by `ApprovedSendJourneyIntegrationTest` and suites #335 and #336 on the Graph stub.
- **API, not browser.** Delivery history was read through the API and the database. The same history and timeline
  views were driven in the browser in #336 and #338 on the Graph stub. No browser run was repeated against Meta.
- **Receipt** is the recipient owner's statement in chat ("received and opened"), recorded as confirmed. It is not an
  independent device check.
- The `close-path` check of `live339.sh` ran once on the real stack (off steps only). After the run, the 9 offline
  `live-test.sh` cases of #140 (success, interrupt, transient and permanent disable failure, backend recreate
  failure, send failure, send timeout, enable failure, status failure) were run against a scratch copy of
  `live339.sh` and all passed (see `outputs.txt`). The copy differs from the committed script only in its `QA_DIR`
  line, so the tests never touch the real helper directory.
- The Callback URL was left at the stopped tunnel (see LIVE-06).
- The recipient's follow-up cadence was cleared by the send helper and not restored (see LIVE-06).

## Defects

None. No finding needed a defect issue or a retest.

## Evidence

- [issue-339-evidence/outputs.txt](issue-339-evidence/outputs.txt): every raw output of the run, sanitized (UUIDs and
  the tunnel host replaced). It holds no phone number, Meta id, provider message id, token or template body.
- [issue-339-evidence/scripts/](issue-339-evidence/scripts/README.md): the wrapper, the trapped window script and the
  helpers.
