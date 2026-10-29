# Phase 3 release certification (issue 140)

Certification run of the scoped Phase 3 release candidate: the messaging core and the manually approved WhatsApp send, composed into the follow-up workbench.

**Tested on `main` @ `f4cb0ec`** (merge of PR #292), which includes:
- the composed workbench (PR #291);
- the T12 resolution route (PR #292);
- the deterministic journey test (PR #282).

Run on 2026-10-27, on the local QA stack built from that commit (`./scripts/dev-env.sh up --seed`), with the backend and frontend images built from `f4cb0ec`. The database is the existing QA database, migrated in place from V32 to V33. It was not reset because it holds the live WhatsApp setup.

Providers:
- **AI:** real OpenAI (`DOKENE_AI_PROVIDER=openai`), except in the suites that use the fault-injection stub, which are labelled.
- **Meta:** Graph API `v23.0`. It was called live only for the template and ownership reads and for the authorized sends listed under [Live provider legs](#live-provider-legs).

All data is synthetic, except the one consenting test recipient of the live sends. This report proves the scenarios it lists; it does not claim that no other defect exists.

**Re-verified on `main` @ `80c7ab7`** (2026-10-28). Its code is identical to `38b5cb9`, which adds the #293 fix ([PR #296](https://github.com/stevdrey/dokene/pull/296)) to `f4cb0ec`. See [Re-verification on `80c7ab7`](#re-verification-on-80c7ab7).

**Overall verdict: BLOCKED — not certified.** #140 and #149 require both tracks to pass on the final candidate, with no required case left FAIL or BLOCKED. One required gate is still open:
- **Real status callbacks: BLOCKED.** The Meta app is unpublished, so no real `delivered`/`read` callback updated a Dokene message. Meta's acceptance and the recipient's confirmation prove the two sends, not the callback reconciliation.

The security gate is closed. P3-KILL-07 failed on `f4cb0ec` ([#293](https://github.com/stevdrey/dokene/issues/293), Low). #149 retested it on `38b5cb9` (kill suite 49 PASS, 0 FAIL), and no defect found by #149 is still open.

Every individual leg recorded below as PASS keeps its evidence. Certification needs the callback gate to pass on the candidate, or an explicitly agreed and recorded change to the acceptance scope.

## Results

| Track | Result | Evidence / notes |
| --- | --- | --- |
| Deterministic backend journey | **PASS** | `./gradlew check` on this commit: 186 suites, 2295 tests, 0 failures, 0 errors, 0 skipped. It includes `ApprovedSendJourneyIntegrationTest`: synthetic customer → consent/purchase → AI draft → explicit, revision-bound approval → manual send through the real Graph adapter (loopback Meta) → signed `delivered`/`failed` callbacks. It also covers duplicate clicks, drift, opt-out, tenant switch, integration disable and uncertain outcomes, each refusal with zero provider requests. |
| Frontend | **PASS** | `npm test`: 46 files, 1103 tests passed (see the observation below). `npm run build`: passed. The composed workbench messaging tests from #291 are included. |
| Phase 3 security (#149) | **PASS after retest** | [PR #294](https://github.com/stevdrey/dokene/pull/294), [report](issue-149-phase-3-security-verification.md), on an isolated stack with the Graph stub. On `f4cb0ec`: 116 PASS, 1 FAIL (P3-KILL-07, [#293](https://github.com/stevdrey/dokene/issues/293), Low: an unexpected switch-lookup failure answered Spring's default 500 body instead of the problem body; the send was still refused, nothing was written and the provider was not called), 2 BLOCKED (metrics halves with no exposed endpoint by design, covered in process). On `38b5cb9`: kill suite 49 PASS (P3-KILL-07 included), RLS 63, hook 35 and browser 21 PASS, all with 0 FAIL. The other #149 suites (approval, duplicate, IDOR, permission) ran on `f4cb0ec` only; #296 changed only the error handling of unexpected failures. #265 and #266 retested fixed. |
| Phase 3 browser journey on the real stack | **PASS (16/16; hardened script 19/19)** | See [Phase 3 browser journey](#phase-3-browser-journey). |
| Live Meta send, Spanish template | **PASS** | See [Live provider legs](#live-provider-legs). |
| Live Meta send, English template | **PASS** | See [Live provider legs](#live-provider-legs). |
| Live status callbacks (`delivered`/`read`) | **BLOCKED** | The Meta app is in development mode, which delivers only dashboard test webhooks and no production status callbacks. 0 inbox rows arrived for the live sends. The deterministic callback path is covered by `ApprovedSendJourneyIntegrationTest`, and the parser for the live status shape is covered by #289. |
| Live OpenAI | **PASS** | Real drafts in the browser journey, suite #121 d (prompt injection, grounding) and the UI suite. See below. |
| Phase 1/2 regression | **PASS with known limitations** | See [Phase 1/2 regression](#phase-12-regression). |

## Phase 3 browser journey

Script `phase3-ui.mjs`. It ran on the live-override stack (frontend `:5174`) in the QA tenant that holds the real Meta integration, with the **global and tenant outbound switches off**. The customer is synthetic.

| Step | Result |
| --- | --- |
| The operator opens a due synthetic customer from the queue | PASS |
| A live OpenAI recommendation offers a draft, and a live draft fills the editable panel | PASS |
| "Revisar y enviar" is offered next to the draft. The review refuses to submit while the intent the AI chose (`REPEAT_PURCHASE`) has no approved template | PASS. Fails closed with "No hay una plantilla aprobada…" |
| A temporary `REPEAT_PURCHASE` mapping is verified `APPROVED` through Graph | PASS. Disabled again afterwards (`restore-templates.py`) |
| Submit: pending approval, not sent. The detail shows "Lo que recibirá el cliente" | PASS |
| Approval bound to the reviewed template preview | PASS |
| Send is refused with "Los envíos están desactivados en la plataforma." | PASS |
| Server side: `AI_DRAFT`, `APPROVED`, 0 attempts, no provider id | PASS |
| Delivery history in the workbench shows the message as "Aprobado" | PASS |
| The viewer opens the same customer: no "Revisar y enviar", "Aprobar" or "Enviar" controls, history still visible | PASS |
| The synthetic message is cancelled through the UI: `CANCELLED`, still 0 attempts | PASS |

Before this run, the live model was asked about a customer whose only purchase was the same day. It answered with no recommendation ("el historial de compras es insuficiente") rather than invent a follow-up. That is the intended grounded behaviour, so the fixture's purchase was moved 60 days back.

Two later versions of `phase3-ui.mjs` came out of review. The first escapes every value it puts into page code (`safeJson`) and gave 19/19 on the same stack, because it also took the temporary-mapping branch. The second adds:
- cleanup in a `finally` block, so a failure never leaves the temporary mapping or an open synthetic message behind;
- checks scoped to the delivery-history section;
- an exact pending-approval assertion.

That last version was rerun after the Meta test token was refreshed (the expired token had shown as a Graph refresh `503 PROVIDER_UNAVAILABLE`). Result: **19/19**, including the exact pending-approval check, the history checks scoped to the delivery-history section and the cleanup that disabled the temporary mapping. The output is in the `phase3-ui-hardened.txt` section of the evidence.

## Live provider legs

Procedure: [phase-3-live-meta-procedure.md](phase-3-live-meta-procedure.md). Each send was one explicitly authorized template send to the consenting test recipient, through Dokene's normal API (`send140.py`, fixed idempotency keys):
- submit with `If-Match` = policy version;
- approve with `templateRevision`;
- send.

The recipient owner authorized lowering `dokene.messaging.min-contact-interval` to `1m` on the QA stack for this run only. It was restored to `24h`, together with both switches, after each send.

| Leg | Result |
| --- | --- |
| Integration proof and template refresh through Graph | PASS: `webhookVerified=true`, `GENERAL_FOLLOW_UP` `APPROVED`/`sendable` |
| Spanish template (`es-419`), 21:10:44Z | **PASS**: 1 attempt, `SENT`, `outcomeUnknown=false`, no failure category. Timeline: `SUBMITTED` → `APPROVED` → `SEND_REQUESTED` → `SEND_ATTEMPT_STARTED` → `SENT`. **The recipient confirmed receipt.** |
| English template (`en-US`), 21:21:13Z | **PASS**: 1 attempt, `SENT`, `outcomeUnknown=false`, no failure category, the same timeline. **The recipient confirmed receipt.** `GENERAL_FOLLOW_UP` was mapped to the English template for this send and restored to the Spanish one afterwards (`APPROVED`/`sendable`, language `es`). |
| Windows closed | PASS: tenant `outboundEnabled=false`, global `false`, interval `24h`, confirmed after each send |
| Status callbacks | **BLOCKED** (app unpublished). 0 inbox rows. The messages stay `SENT`, not `DELIVERED`, in Dokene. |

## Phase 1/2 regression

The Path B drivers of campaign #118 were rerun on this commit (`regress.sh`, `rerun-ui.sh`). During the run, the first driver started #121 e, f and g before the stub. It was stopped after `e` and resumed from `e` with the stub; the published `regress.sh` runs them in the stub phase.

| Suite | Result | Notes |
| --- | --- | --- |
| #119 API `v159`, `v162`, `recurrence` | **PASS** 28/28, 45/45, 16/16 | |
| #119 `reg-api` | **Reused** (clean-DB run on `acb7856`, 2026-10-24) | The script needs a clean database (fixed tenant names, phone numbers and counts), so on this database its 34 FAILs are duplicates (`409`) and their follow-on `404`s. No tenant, customer, consent, purchase or follow-up code changed between `acb7856` and `f4cb0ec` (only audit, messaging, integration and V33), so the earlier clean result still holds. |
| #121 a, b, c, e, f, h, i, j, l, m | **PASS** 53, 63, 44, 26, 32, 26, 14, 29, 4, 11 checks, 0 FAIL | `e` and `f` need the fault-injection stub. A first pass of `e` without it was a harness error (23 FAILs, `provider_calls=0`). Rerun with the stub, it passes. `k` is a fixture (no checks). |
| #121 d (live prompt injection and grounding) | **PASS** 109/109 after review | The 2 automated FAILs are the script's Spanish-word heuristic on two short, all-Spanish drafts (`notes-es2-en0`, `notes-es1-en0`, no English words). Reviewed by reading both bodies. |
| #121 g (bypass probes) | **Unchanged** 22 PASS / 6 FAIL | Identical to the `acb7856` baseline. One is the control line, which is designed to fail. The other five (number words, `mitad`, `10 lucas`, English discount and free shipping) are documented residual risk (ADR 0018, #184). |
| #122 `ux01`–`ux05`, `ux06b`, `ux08`, `ux10_11`, `ux11d` | **PASS** | |
| #122 `ux06` | **PASS for every check it reached** | All overflow checks pass at 1440 and 390 px. Its live-AI tail could not find its fixture, because queue search filters only loaded pages and this database has 107 due customers (a clean one has 81). The same detail panel is covered by the browser journey above. |
| #122 `ux07` | **Unchanged FAIL + harness limit** | The 401-message FAIL is the same as the `acb7856` baseline. Its rate-limit scenario looks up a fixture in the first 50 queue items and could not find it on this database. |
| #122 `ux09` | **Harness limit** | Its 4 FAILs assert the clean-database total of 81 cards. With 107 due customers the UI correctly loads 100 and keeps "Cargar más seguimientos". |

## Re-verification on `80c7ab7`

`main` @ `80c7ab7` adds to `f4cb0ec` the #293 fix (PR #296: a closed `INTERNAL_ERROR` problem body for unexpected failures on Phase 3 routes, and the frontend reading it as an uncertain outcome) and the documentation of PR #294 and PR #295. The checks below ran on it on 2026-10-28. The QA stack images were rebuilt from it with outbound off; there was no live send. Raw outputs are in [reverify-80c7ab7.txt](phase-3-evidence/reverify-80c7ab7.txt); the regression driver is `reverify-regress.sh`.

| Check | Result | Notes |
| --- | --- | --- |
| `./gradlew check` (JDK 26) | **PASS** | 190 suites, 2311 tests, 0 failures, 0 errors, 0 skipped. It includes the #296 tests (`Phase3UnexpectedFailureIntegrationTest`, `Phase3ErrorPageSystemTest`). |
| `npm test`, `npm run build` | **PASS** | 1104/1104 tests with `vitest --maxWorkers=4`. The first full-parallel run gave 30 failures under a load average of about 32, the same timing pattern as the observation below. The build passes. |
| Phase 3 browser journey (`phase3-ui.mjs`) | **PASS 19/19** | The same steps as on `f4cb0ec`, with real OpenAI and the template verified through Graph. The send is refused by the global switch, with 0 attempts and no provider id. A first attempt stopped at the Graph template refresh (`503 PROVIDER_UNAVAILABLE`, expired Meta test token) and its cleanup disabled the temporary mapping; after the token was refreshed, the full run passed. |
| #119 `v159`, `v162` | **PASS** 28/28, 45/45 | |
| #119 `recurrence` | **PASS for every check it can read**, 15/16 | The one FAIL is a harness limit: "back in the queue as OVERDUE" reads only the first 100 queue items. This tenant now has 128 due customers, and page 1 is 100 `OVERDUE` customers older than the test customer, which is overdue by one day and therefore on page 2. The eligibility check of the same step (`OVERDUE` by one day) passes, and so do the later cycles. |
| #121 a, b, c | **PASS** 53, 63, 44 checks, 0 FAIL | Authentication, CSRF, roles and tenant isolation are unchanged by the new error handling. |
| #121 d (live prompt injection and grounding) | **PASS** 107/109, the same 2 heuristic FAILs as on `f4cb0ec` | Both are the script's Spanish-word heuristic on two short, all-Spanish drafts (`notes-es2-en0`, no English words), with locale `es-419`. |

The live Meta legs, the #121 e–m and #122 suites were not rerun: #296 does not touch the send path, the AI path or the Phase 1/2 UI, and their `f4cb0ec` results stand.

## Observations (not filed)

- **The frontend suite is timing sensitive under heavy load.** The first `npm test` ran while the QA stack and the isolated #149 stack were both up (load average about 9). It had 31 failures in 14 files: 13 were the 5 s default test timeout, and the rest were elements that had not rendered in time. Run again by itself, the same commit gave 46/46 files and 1103/1103 tests. The #291 session also reported one unexplained intermittent failure. Raising the timeout or making the slow tests wait on state would remove the noise. This does not affect the product.

## Findings

| Issue | Severity | Status |
| --- | --- | --- |
| [#293](https://github.com/stevdrey/dokene/issues/293) | Low | Fixed on `main` by [PR #296](https://github.com/stevdrey/dokene/pull/296) after the `f4cb0ec` run: a Phase 3 route now answers unexpected failures with the problem body. Verified by the #149 retest of P3-KILL-07 on `38b5cb9`. |
| #265, #266 | Low | Fixed and retested by #149 |

No new defect was found by the regression, the browser journey or the live legs.

## Limitations

- **Real status callbacks were not observed.** That needs the Meta app published. Until then the live callback leg is BLOCKED, not PASS.
- **The database was migrated, not clean.** Scripts that assume clean-database counts are marked as harness limits, with the reason, and the evidence for those checks is reused or covered elsewhere as stated.
- **Screen reader checks** (#122 UX-04, real assistive technology) remain outside this automated run.
- **The minimum contact interval was lowered to `1m`** for the live sends, with the recipient owner's authorization, on the QA stack only. It was restored to `24h` and confirmed.
- **This is a scoped certification.** It does not include a recurring scheduler, campaigns or auto-send, which are out of Phase 3 by design.

## Evidence kept in this repository

- [phase-3-evidence/outputs.txt](phase-3-evidence/outputs.txt): one section per raw output file of this run, including the live-leg summary without identifiers.
- [phase-3-evidence/reverify-80c7ab7.txt](phase-3-evidence/reverify-80c7ab7.txt): the raw outputs of the re-verification on `80c7ab7`.
- [phase-3-evidence/scripts/](phase-3-evidence/scripts/README.md): the drivers, the browser journey, the live helpers and the live-override wrapper.
- The #149 evidence lives in [issue-149-evidence/](issue-149-evidence/) (PR #294).
