# Phase 3 security verification (issue 149)

Execution of the Phase 3 abuse cases from [`phase-3-security-cases.md`](./phase-3-security-cases.md) against the
integrated local stack. This is a **preparation run on `main`**, not the acceptance run on the composed candidate of
[#140]: several routes and screens the matrix names do not exist yet on this commit, and those rows are `BLOCKED` with
the owing issue. The harness is checked in so the same run repeats on the candidate.

| | |
| --- | --- |
| Commit under test | `bdbf061` (`main`, merge of PR #262) |
| Date | 2026-10-21 |
| Stack | `docker compose` dev stack, backend and frontend images built from the commit, database at Flyway V29 |
| Provider | Graph API stub on `127.0.0.1:18080` inside the backend's network namespace; **no call left the machine** |
| Secrets | local secret source with canary values (`CANARY-TOKEN`, `CANARY-APP-SECRET`, `CANARY-VERIFY`, a synthetic tenant B token) |
| Harness | [`issue-149-evidence/scripts/`](./issue-149-evidence/scripts/README.md); raw results in [`issue-149-evidence/out/`](./issue-149-evidence/out/) |

No product code was changed for this run. Every expectation that was adjusted while building the harness is a harness
correction, listed under [Harness corrections](#harness-corrections) with the reason.

## Verdict

| | Cases | Checks |
| --- | --- | --- |
| PASS | 107 | 624 |
| FAIL | 3 | 5 |
| BLOCKED | 9 | 9 |
| Total | 119 of 119 matrix ids | 638 |

Every `FAIL` maps to finding 1 or 2 below. Every `BLOCKED` is a route or screen missing on this commit, or a
measurement the local stack cannot expose; each one names what covers it instead. Zero outbound calls left the stub
during denial cases; the stub log records the bearer class (`A`, `B`, `other`, `none`) of every call it did receive.

Read the table as: a case is `FAIL` when any of its checks failed, `BLOCKED` when it could not be driven on this
commit and nothing failed, `PASS` otherwise. The channel column lists every channel that contributed. The raw lines
(one per check, with the values seen) are in `out/results.tsv`; `out/verdicts.tsv` is the per-case reduction.

## Findings

Findings 1 and 2 are filed as #265 and #266. None of these were fixed during the run. Severity is my reading; none of them lets a send happen or a secret out.

1. **The cancellation route writes two `AUTHORIZATION_DENIED` audit rows for one denied request (Low, audit noise; filed as #265).**
   `POST /api/messages/{id}/cancellations` is guarded with `anyOf(MESSAGE_DRAFT, MESSAGE_APPROVE)`. When `A-VIEWER`
   calls it, each permission of the pair is evaluated and audited, so the table gets two rows for one `403`, and the
   latest row names `MESSAGE_APPROVE` although the viewer was trying to cancel. P3-PERM-03 and P3-PERM-05 expect one
   row per denial. Every other denial writes exactly one row. Repro: `sec149-perm.sh`, the VIEWER cancel checks.
2. **`POST /webhooks/other` answers `403` with a `Set-Cookie: JSESSIONID` (Low, hygiene; filed as #266).** The webhook sibling path
   is not a handler (good), but it falls through to the session-backed chain and mints a session cookie, both direct
   and through the proxy. `/webhooks/whatsapp` itself never sets a cookie. P3-CSRF-05 expects no cookie on any path
   under `/webhooks`. Repro: `sec149-csrf.sh`, the `/webhooks/other` checks.
3. **No `resolutions` route on this commit (expected gap, owed by #138, T12).** P3-PERM-07, P3-DUP-08, P3-APPR-12 and
   the resolution half of P3-IDOR-05 are `BLOCKED`. Not a defect, listed so the candidate run does not forget it.
   The route is served from #140 onwards (`issue-140-t12-resolution-verification.md`); these cases stay `BLOCKED`
   for this commit.
4. **No integration or messaging settings screen is mounted (expected gap, owed by #144/#146).** The `Configuración`
   tab is a placeholder; `IntegrationStatusPanel` exists but nothing renders it. The screen halves of P3-SEC-08 and
   P3-KILL-03 are `BLOCKED`.

Deviations from the matrix that are not defects:

- **P3-DUP-10** refuses the second submit with `FOLLOW_UP_INELIGIBLE`, not `MESSAGE_ALREADY_OPEN` or
  `CONTACT_FREQUENCY_EXCEEDED`. The send anchors the follow-up cadence, so the customer is no longer due and the
  cadence guard answers before the open-message guard is reached. The property (refused, nothing written) holds; the
  open-message guard itself is proved in process by
  `OutboundMessagingIntegrationTest.concurrentSubmitsForOneCustomerLeaveExactlyOneOpenMessage`.
- **P3-HOOK-07**: a repeated `delivered` report after `read` is deduplicated at the inbox (the event id is tenant,
  provider message id and status), so it leaves no event; only the two unseen statuses (`sent`, `failed`) are recorded
  as non-applied events. The message stays `READ` with no failure stored.
- **P3-DUP-09**: a provider `5xx` is an unknown outcome under ADR 0027 and parks the message (`SENDING`,
  `outcome_unknown`), which is P3-DUP-12's behaviour. The transient failure the row describes is a provider `429`;
  with that the message reaches `FAILED` after three attempts and a fourth send is refused.

## What could not be driven

| Row | Why | Covered instead by |
| --- | --- | --- |
| P3-SEC-07 (metrics half), P3-SEC-11 | No actuator route is reachable on the local stack (`/actuator/*` answers `401`) | `WebhookTelemetryTest` and the dispatch telemetry tests, in process |
| P3-KILL-07 | Deleting the integration row needs a database role with `DELETE` on `tenant_messaging_integrations` (the runtime role has none) and destroys the fixture | `WhatsAppOutboundSafetyControlTest`, in process |
| P3-CONS-08 | The ordering (consent revoked between approval and send) is observed through the API, not forced inside the transaction | `OutboundMessagingGuardIntegrationTest.approveReChecksConsentAndDoNotContactAndWritesNothingWhenRefused` |
| P3-SEC-09 | Internal call with no route | `LocalIntegrationSecretResolverTest`, `GraphApiMessagingProviderTest` (parameterized), in process |
| Real Meta | Nothing talks to Meta; the stub answers every Graph call. Template approval, phone ownership and send answers are the stub's | Out of scope for a preparation run |

## Harness corrections

Expectations I changed while building the harness, all on the harness side:

- HOOK-05 queried `status`; the column is `state`. HOOK-05 and HOOK-07 count rows of this run only.
- HOOK-10 sends the 4.5 MB body with `Expect:` cleared, otherwise curl reports the `100 Continue` line as the status.
- SEC-05 read the status of the events `GET` instead of the send.
- SEC-06 used a fixed canary phone, which collided with the customer of the previous run.
- DUP-13 waits until earlier attempt rows leave the one-minute window: the limit counts attempt rows, across restarts.
- SEC-08 treats the BFF port (`localhost:8080`, where the OAuth2 callback lands) as Dokene's own host.

## Repeating the run on the candidate

```sh
docker compose up -d --build                                             # images from the candidate commit
bash docs/verification/issue-149-evidence/scripts/fixtures149.sh         # once per database
QA149_PW_DIR=<dir with node_modules/playwright-core> \
bash docs/verification/issue-149-evidence/scripts/run-all.sh
```

Then replace the table below from `out/verdicts.tsv` and re-check the `BLOCKED` rows above: on the candidate the
resolutions route and the screens should turn them into `PASS` or `FAIL`.

## Per-case results

| Case | Verdict | Channels | Checks | Note |
| --- | --- | --- | --- | --- |
| P3-PERM-01 | PASS | HTTP | 4 |  |
| P3-PERM-02 | PASS | HTTP, Automated | 8 |  |
| P3-PERM-03 | FAIL | HTTP | 7 | finding 1 (#265): two denial rows for one denied cancel |
| P3-PERM-04 | PASS | HTTP | 14 |  |
| P3-PERM-05 | FAIL | HTTP | 7 | finding 1 (#265): two denial rows for one denied cancel |
| P3-PERM-06 | PASS | HTTP | 8 |  |
| P3-PERM-07 | BLOCKED | HTTP | 1 | POST /api/messages/{id}/resolutions is not deployed on this commit (404); T12 is owed by #138 |
| P3-PERM-08 | PASS | HTTP | 4 |  |
| P3-PERM-09 | PASS | HTTP | 7 |  |
| P3-PERM-10 | PASS | HTTP | 4 |  |
| P3-PERM-11 | PASS | HTTP | 10 |  |
| P3-PERM-12 | PASS | HTTP, Automated | 12 |  |
| P3-PERM-13 | PASS | In process, Automated | 2 |  |
| P3-PERM-14 | PASS | HTTP | 1 |  |
| P3-PERM-15 | PASS | HTTP | 4 |  |
| P3-PERM-16 | PASS | HTTP, Automated | 6 |  |
| P3-PERM-17 | PASS | HTTP | 6 |  |
| P3-PERM-18 | PASS | HTTP | 3 |  |
| P3-PERM-19 | PASS | HTTP, Automated | 8 |  |
| P3-IDOR-01 | PASS | HTTP | 2 |  |
| P3-IDOR-02 | PASS | HTTP | 2 |  |
| P3-IDOR-03 | PASS | HTTP, Automated | 3 |  |
| P3-IDOR-04 | PASS | HTTP, Automated | 8 |  |
| P3-IDOR-05 | BLOCKED | HTTP | 4 | resolutions route not deployed (404): the unknown outcome half waits for T12 (#138) |
| P3-IDOR-06 | PASS | HTTP, Automated | 8 |  |
| P3-IDOR-07 | PASS | HTTP | 5 |  |
| P3-IDOR-08 | PASS | HTTP, Automated | 4 |  |
| P3-IDOR-09 | PASS | HTTP | 6 |  |
| P3-IDOR-10 | PASS | HTTP | 3 |  |
| P3-RLS-01 | PASS | Database, Automated | 26 |  |
| P3-RLS-02 | PASS | Database, Automated | 9 |  |
| P3-RLS-03 | PASS | Database, Automated | 4 |  |
| P3-RLS-04 | PASS | Database, Automated | 11 |  |
| P3-CSRF-01 | PASS | HTTP, Automated | 14 |  |
| P3-CSRF-02 | PASS | HTTP | 23 |  |
| P3-CSRF-03 | PASS | Database, Browser | 4 |  |
| P3-CSRF-04 | PASS | HTTP, Automated | 3 |  |
| P3-CSRF-05 | FAIL | HTTP, Automated | 13 | finding 2 (#266): `Set-Cookie` on `/webhooks/other` |
| P3-APPR-01 | PASS | HTTP, Automated | 4 |  |
| P3-APPR-02 | PASS | HTTP, Automated | 5 |  |
| P3-APPR-03 | PASS | HTTP | 8 |  |
| P3-APPR-04 | PASS | HTTP | 3 |  |
| P3-APPR-05 | PASS | HTTP, Automated | 4 |  |
| P3-APPR-06 | PASS | HTTP, Automated | 4 |  |
| P3-APPR-07 | PASS | HTTP, Automated | 4 |  |
| P3-APPR-08 | PASS | HTTP, Automated | 10 |  |
| P3-APPR-09 | PASS | HTTP, Automated | 6 |  |
| P3-APPR-10 | PASS | HTTP | 7 |  |
| P3-APPR-11 | PASS | HTTP | 10 |  |
| P3-APPR-12 | BLOCKED | HTTP | 1 | POST /api/messages/{id}/resolutions is not deployed on this commit; T12 is owed by #138 |
| P3-APPR-13 | PASS | HTTP | 4 |  |
| P3-APPR-14 | PASS | HTTP | 4 |  |
| P3-TMPL-01 | PASS | HTTP | 8 |  |
| P3-TMPL-02 | PASS | HTTP, Automated | 6 |  |
| P3-TMPL-03 | PASS | HTTP, Automated | 3 |  |
| P3-TMPL-04 | PASS | HTTP, Automated | 4 |  |
| P3-TMPL-05 | PASS | HTTP, Automated | 8 |  |
| P3-TMPL-06 | PASS | HTTP, Automated | 14 |  |
| P3-TMPL-07 | PASS | HTTP, Automated | 10 |  |
| P3-TMPL-08 | PASS | HTTP | 2 |  |
| P3-CONS-01 | PASS | HTTP, Automated | 9 |  |
| P3-CONS-02 | PASS | HTTP, Automated | 3 |  |
| P3-CONS-03 | PASS | HTTP | 2 |  |
| P3-CONS-04 | PASS | HTTP | 3 |  |
| P3-CONS-05 | PASS | HTTP | 3 |  |
| P3-CONS-06 | PASS | HTTP | 3 |  |
| P3-CONS-07 | PASS | HTTP | 3 |  |
| P3-CONS-08 | PASS | HTTP | 3 |  |
| P3-CONS-09 | PASS | HTTP | 4 |  |
| P3-CONS-10 | PASS | HTTP | 2 |  |
| P3-DUP-01 | PASS | HTTP | 2 |  |
| P3-DUP-02 | PASS | HTTP | 2 |  |
| P3-DUP-03 | PASS | HTTP, Automated | 7 |  |
| P3-DUP-04 | PASS | HTTP, Automated | 2 |  |
| P3-DUP-05 | PASS | Database, Automated | 4 |  |
| P3-DUP-06 | PASS | HTTP, Automated | 2 |  |
| P3-DUP-07 | PASS | HTTP, Automated | 4 |  |
| P3-DUP-08 | BLOCKED | HTTP, Automated | 2 | POST /api/messages/{id}/resolutions is not deployed on this commit (404); T12 is owed by #138 |
| P3-DUP-09 | PASS | HTTP, Automated | 5 |  |
| P3-DUP-10 | PASS | HTTP | 3 |  |
| P3-DUP-11 | PASS | HTTP | 7 |  |
| P3-DUP-12 | PASS | HTTP | 3 |  |
| P3-DUP-13 | PASS | HTTP | 2 |  |
| P3-HOOK-01 | PASS | HTTP | 4 |  |
| P3-HOOK-02 | PASS | HTTP | 2 |  |
| P3-HOOK-03 | PASS | HTTP | 6 |  |
| P3-HOOK-04 | PASS | HTTP, Automated | 3 |  |
| P3-HOOK-05 | PASS | HTTP, Automated | 4 |  |
| P3-HOOK-06 | PASS | HTTP, Automated | 2 |  |
| P3-HOOK-07 | PASS | HTTP, Automated | 5 |  |
| P3-HOOK-08 | PASS | HTTP, Automated | 4 |  |
| P3-HOOK-09 | PASS | HTTP | 3 |  |
| P3-HOOK-10 | PASS | HTTP | 2 |  |
| P3-HOOK-11 | PASS | HTTP, Automated | 2 |  |
| P3-HOOK-12 | PASS | In process, Automated | 4 |  |
| P3-HOOK-13 | PASS | Database, Automated | 5 |  |
| P3-HOOK-14 | PASS | HTTP, Automated | 4 |  |
| P3-HOOK-15 | PASS | HTTP, Automated | 4 |  |
| P3-KILL-01 | PASS | HTTP, Automated | 5 |  |
| P3-KILL-02 | PASS | HTTP, Automated | 5 |  |
| P3-KILL-03 | BLOCKED | HTTP, Browser, Automated | 10 | no messaging or integration settings screen is mounted on this commit (Configuración is a placeholder); the screen half of the row needs #144/#146 screens |
| P3-KILL-04 | PASS | HTTP, Automated | 5 |  |
| P3-KILL-05 | PASS | HTTP, Automated | 18 |  |
| P3-KILL-06 | PASS | HTTP, Automated | 4 |  |
| P3-KILL-07 | BLOCKED | HTTP, In process, Automated | 3 | not driven on the real stack: deleting the integration row needs a database role with DELETE on tenant_messaging_integrations (the runtime role has none) and... |
| P3-KILL-08 | PASS | HTTP, Automated | 3 |  |
| P3-KILL-09 | PASS | HTTP, Automated | 3 |  |
| P3-KILL-10 | PASS | HTTP | 3 |  |
| P3-SEC-01 | PASS | HTTP | 4 |  |
| P3-SEC-02 | PASS | HTTP | 7 |  |
| P3-SEC-03 | PASS | HTTP | 4 |  |
| P3-SEC-04 | PASS | HTTP | 2 |  |
| P3-SEC-05 | PASS | HTTP, Automated | 6 |  |
| P3-SEC-06 | PASS | HTTP, Automated | 4 |  |
| P3-SEC-07 | BLOCKED | HTTP, Browser | 8 | metrics output not reachable over HTTP (actuator 401/401); the metrics half and the browser screens are in sec149-browser.mjs |
| P3-SEC-08 | BLOCKED | Browser | 7 | the integration screen is not mounted in the shell on this commit (the Configuración tab is a placeholder; IntegrationStatusPanel exists but nothing renders ... |
| P3-SEC-09 | PASS | In process | 2 |  |
| P3-SEC-10 | PASS | HTTP | 2 |  |
| P3-SEC-11 | BLOCKED | HTTP, In process | 2 | metric names and tags are not reachable over HTTP on the local stack (no actuator route); the telemetry tag rules are covered in process by WebhookTelemetryT... |
