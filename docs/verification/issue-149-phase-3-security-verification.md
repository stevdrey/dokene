# Phase 3 security verification (issue 149)

This is the run of the Phase 3 abuse cases from [`phase-3-security-cases.md`](./phase-3-security-cases.md) against the
composed candidate of [#140]. It is the **acceptance run** that #140's release verdict waits for. It replaces the
preparation run on `bdbf061` that this file used to hold. That run is summarized under
[Preparation run](#preparation-run-on-bdbf061) and kept in git history.

| | |
| --- | --- |
| Commit under test | `f4cb0ec` (`main`, merge of PR #292, after PRs #282, #291 and #292) |
| Date | 2026-10-27 |
| Stack | A second, isolated `docker compose` dev stack (project `dokene-qa149`, images `:qa149` built from the commit, host ports 28080/28081/25173/25432). The shared `dokene-qa-run` stack and the live stack were not touched. Database at Flyway V33. |
| Provider | Graph API stub on `127.0.0.1:18080` inside the backend's network namespace. **No call left the machine and no live Meta send was made.** Real Meta evidence belongs to #140. |
| Secrets | Local secret source with canary values (`CANARY-TOKEN`, `CANARY-APP-SECRET`, `CANARY-VERIFY`, a synthetic tenant B token). |
| Data | The fixture tenants and actors of the catalog (`QA Café Norte` = A, `QA Café Sur` = B, seven actors), synthetic phone numbers and `CANARY-*` strings only. |
| Harness | [`issue-149-evidence/scripts/`](./issue-149-evidence/scripts/README.md). Every check of the run is in [`issue-149-evidence/outputs.txt`](./issue-149-evidence/outputs.txt). |

No product code was changed for this run. Harness changes are listed under [Harness changes](#harness-changes) with
the reason for each.

## Verdict

| | Cases | Checks |
| --- | --- | --- |
| PASS | 116 | 678 |
| FAIL | 1 | 1 |
| BLOCKED | 2 | 2 |
| Total | 119 of 119 catalog ids | 681 |

**The run is not clean.** P3-KILL-07 fails on the error contract, filed as [#293] (Low). The send is still refused,
nothing is written and the provider is not called. The two `BLOCKED` rows are measurements this deployment cannot
expose by design: no metrics endpoint is reachable. Each names the in-process test that covers it. Per the issue
contract, the failure must be fixed and retested before this suite gives #140 a clean verdict.

Denial cases made zero outbound calls. The stub logs the bearer class (`A`, `B`, `other`, `none`) of every call it
receives, and every refusal check compares the stub's send count before and after. RLS and server authorization were
exercised directly, over HTTP and as `dokene_runtime`. Nothing was inferred from hidden buttons.

How to read the table: a case is `FAIL` when any of its checks failed, `BLOCKED` when it could not be driven and
nothing failed, and `PASS` otherwise. The channel column lists every channel that contributed:

- HTTP: through the BFF or the proxy.
- Database: a connection as `dokene_runtime`, or the bootstrap role only to inject a fault.
- Browser: headless Chromium.
- In process: the matrix's internal-call rows, run as tests built from the same commit.
- Automated: every test class the catalog names, run on the same commit.

## Findings

1. **An unexpected failure on a Phase 3 route answers `500` with Spring's default error body (Low, error contract;
   filed as [#293]).** The run injected a fault: the bootstrap role briefly revoked the runtime role's `SELECT` on
   `tenant_messaging_integrations`. The send is then refused with `500`. Nothing is written: no attempt row, event,
   idempotency key or audit row, and no provider call. The message stays `APPROVED`, so "an unreadable switch counts
   as off" holds. The body is `{"timestamp","status","error","path"}` as `application/json`, and `path` echoes the
   message UUID. ADR 0023 §4.4 requires `{type,title,status,code}` as `application/problem+json`, with no identifiers.
   No stack trace or exception text reaches the client. Repro: `sec149-kill.sh`, the P3-KILL-07 block.

Earlier findings, retested on this commit:

- **#265 (two `AUTHORIZATION_DENIED` rows for one denied cancel): fixed.** The P3-PERM-03 and P3-PERM-05 viewer
  cancels now write exactly one denial row each.
- **#266 (`Set-Cookie: JSESSIONID` on `/webhooks/other`): fixed.** P3-CSRF-05 sees no cookie on any `/webhooks` path,
  direct or through the proxy.

Previously `BLOCKED` rows, judged again on this commit:

| Row | On `bdbf061` | On `f4cb0ec` |
| --- | --- | --- |
| P3-PERM-07 | `BLOCKED`: no resolutions route | `PASS`. OPERATOR and VIEWER are denied with one `AUTHORIZATION_DENIED` row naming `INTEGRATION_MANAGE` and nothing else written. OWNER and ADMIN resolve an unknown outcome to `FAILED` (`201`, one resolution row, no provider call). Resolving an `APPROVED` message is `409 INVALID_TRANSITION`. |
| P3-IDOR-05 | `BLOCKED` (resolution half) | `PASS`. `A-ADMIN` resolving tenant B's unknown-outcome message, even with B's current version as `If-Match`, gets `404 NOT_FOUND`. B's message is unchanged and has no resolution row. |
| P3-APPR-12 | `BLOCKED` | `PASS`. The stub answers the send with an id and a non-accepted status, so the message is `SENDING`, its outcome is unknown and it keeps the provider id. A signed `sent` report moves it to `SENT`. The resolution with the held ETag is `409 STALE_VERSION`. With the fresh ETag it is `409 INVALID_TRANSITION`. Nothing is written either time. |
| P3-DUP-08 | `BLOCKED` | `PASS`. `outcome: SENT` is `400 INVALID_INPUT` and writes nothing. `FAILED` gives `201`: the message is `FAILED` with no provider message id, one event, one resolution row and no provider call. A replay with the same key is `200` and writes nothing. |
| P3-SEC-08 | `BLOCKED` (no integration screen) | `PASS`. Configuración renders the WhatsApp integration panel. Its `/api/integrations/whatsapp` and `/api/templates` responses carry no canary and no token field. The screen shows the reference name only. Storage, cookies and the bundle are clean, and every request went to the proxy, the BFF or Keycloak. |
| P3-KILL-03 | `BLOCKED` (screen half) | `PASS`. 173 controls across the four tabs; none names a global or platform switch. Configuración offers only `Editar` and `Verificar propiedad`. The route half was already passing. |
| P3-KILL-07 | `BLOCKED`: a missing row needed `DELETE` | `FAIL` ([#293]): driven by revoking the read instead (finding 1) |

Deviations from the matrix that are not defects. They carry over from the preparation run and still hold:

- **P3-DUP-10** refuses the second submit with `FOLLOW_UP_INELIGIBLE`. The send anchors the cadence, so the cadence
  guard answers before the open-message guard. The open-message guard itself is proved in process.
- **P3-HOOK-07**: a repeated `delivered` after `read` is deduplicated at the inbox. Only the unseen `sent` and `failed`
  are recorded, as non-applied events.
- **P3-DUP-09**: a provider `5xx` is an unknown outcome (ADR 0027). The transient failure is a `429`.
- **P3-HOOK-14**: a report dated before the message existed is stored at the message's `created_at`, as spec 0001
  AC-14 fixes it (window `[created_at, now]`). That instant is earlier than `sent_at` by the approval and dispatch
  time, about 0.2 s here. The cadence anchor does not move.

## What could not be driven

| Row | Why | Covered instead by |
| --- | --- | --- |
| P3-SEC-07 (metrics half only) | No metrics output exists to search: `management.endpoints.access.default: none` and no web exposure, by design (`/actuator/*` answers `401`). Backend log, proxy log, audit table, message events and all four screens pass. | Telemetry tests in process |
| P3-SEC-11 | Same: metric names and tags are not reachable over HTTP | `WebhookTelemetryTest` and the dispatch telemetry tests, in process |
| Real Meta | Nothing talks to Meta; the stub answers every Graph call | #140 owns the single live send |

## Harness changes

All of these are on the harness side, made before or during this run:

- **Isolation** (`isolated149.sh`, `compose.isolated149.yaml`, `stack149.sh`): a second compose project with its own
  image tags and shifted host ports. `CURL_HOME` points at a generated `.curlrc` that maps `localhost:8080`, `8081`
  and `5173` to those ports, and Chromium uses a small mapping proxy, so origins, Host headers and OIDC redirect URIs
  are unchanged. `stack149.sh` takes the compose base files and stub container name from the environment.
- **T12 coverage**: the `resolve` and `ready_unknown` helpers and the resolutions table count. The stub gets a
  `paused` send mode (an id with a non-accepted status), so an unknown outcome keeps a provider id for P3-APPR-12.
  P3-PERM-07, P3-IDOR-05, P3-DUP-08 and P3-APPR-12 now assert what the catalog row says.
- **Screens**: the browser script waits for the integration panel and reads its API responses. It records the names
  of every control for P3-KILL-03 and replaces the hard-coded `BLOCKED` records.
- **RLS**: `outbound_message_resolutions` (V33) gets the P3-RLS-01 and P3-RLS-02 checks: forced RLS, no `DELETE` or
  `TRUNCATE`, tenant-scoped reads, and forged or expired capabilities denied.
- **P3-KILL-07**: driven by a reversible revoke on the disposable database. The exact grants are checked after the
  restore, and a control send succeeds.
- **P3-KILL-07 body check, strengthened after review** (for the #293 retest): this run's check only required a `5xx`
  and a truthy `code`, which a partial fix would also pass, for example a `code` added to the default body while
  `path` still echoes the message id. It now requires `500`, `application/problem+json` and exactly
  `{type:"about:blank", title:"Internal Server Error", status:500, code:"INTERNAL_ERROR"}` (`problem_exact` in
  `qa149.sh`). Before the live check, `selftest149.sh` runs 16 offline controls: the valid body plus missing, extra and
  identifier members, unknown and wrong codes, a mismatched or string status, the wrong HTTP status, the wrong media
  type, non-JSON and empty bodies. The recorded `FAIL` on `f4cb0ec` would also fail the stronger check.
- **Corrections after the first pass**. The `hook`, `rls` and `kill` suites were rerun and their lines replace the
  first-pass lines in `outputs.txt`, which also keeps the replaced non-`PASS` lines:
  - P3-HOOK-14 compared the stored time with `sent_at`, which is stricter than AC-14. It now checks
    `delivered_at = created_at` and that the anchor does not move. On `bdbf061` the old check passed only because the
    two values rounded to the same second.
  - P3-HOOK-05 counted every parked row in tenant A, so a row left by the previous pass a minute earlier was counted.
    It now follows its own inbox row by id.
  - P3-KILL-07 first read the ETag after the revoke, so the request stopped at `400` before reaching the switch read.
    It now reads the ETag first.

## Repeating the run

```sh
. docs/verification/issue-149-evidence/scripts/isolated149.sh      # only when another dev stack is running
docker compose up -d --build
scripts/seed-local-qa.sh
bash docs/verification/issue-149-evidence/scripts/fixtures149.sh
QA149_PW_DIR=<dir with node_modules/playwright-core> QA149_CHROME=<chrome-headless-shell> \
bash docs/verification/issue-149-evidence/scripts/run-all.sh
```

The backend tests need JDK 26 (the in-process suite runs `./gradlew test` with Testcontainers). This run used
Temurin 26.0.2, Node 24 and `playwright-core` 1.64.1 with Chromium headless shell 1248.

## Preparation run on `bdbf061`

The preparation run (2026-10-21) gave 107 `PASS`, 3 `FAIL` and 9 `BLOCKED`. Its failures were #265 and #266, both
fixed and retested above. The full report is in git history at commit `941693d`.

## Per-case results

| Case | Verdict | Channels | Checks | Note |
| --- | --- | --- | --- | --- |
| P3-PERM-01 | PASS | HTTP | 4 |  |
| P3-PERM-02 | PASS | HTTP, Automated | 8 |  |
| P3-PERM-03 | PASS | HTTP | 7 | #265 retest: one denial row per denied cancel |
| P3-PERM-04 | PASS | HTTP | 14 |  |
| P3-PERM-05 | PASS | HTTP | 7 | #265 retest: one denial row per denied cancel |
| P3-PERM-06 | PASS | HTTP | 8 |  |
| P3-PERM-07 | PASS | HTTP, Automated | 14 | was BLOCKED on `bdbf061`; T12 route served now |
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
| P3-IDOR-05 | PASS | HTTP, Automated | 7 | was BLOCKED on `bdbf061`; resolution half now driven |
| P3-IDOR-06 | PASS | HTTP, Automated | 8 |  |
| P3-IDOR-07 | PASS | HTTP | 5 |  |
| P3-IDOR-08 | PASS | HTTP, Automated | 4 |  |
| P3-IDOR-09 | PASS | HTTP | 6 |  |
| P3-IDOR-10 | PASS | HTTP | 3 |  |
| P3-RLS-01 | PASS | Database, Automated | 30 |  |
| P3-RLS-02 | PASS | Database, Automated | 10 |  |
| P3-RLS-03 | PASS | Database, Automated | 4 |  |
| P3-RLS-04 | PASS | Database, Automated | 11 |  |
| P3-CSRF-01 | PASS | HTTP, Automated | 14 |  |
| P3-CSRF-02 | PASS | HTTP | 23 |  |
| P3-CSRF-03 | PASS | Database, Browser | 4 |  |
| P3-CSRF-04 | PASS | HTTP, Automated | 3 |  |
| P3-CSRF-05 | PASS | HTTP, Automated | 13 | #266 retest: no `Set-Cookie` under `/webhooks` |
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
| P3-APPR-13 | PASS | HTTP | 4 |  |
| P3-APPR-11 | PASS | HTTP | 10 |  |
| P3-APPR-14 | PASS | HTTP | 4 |  |
| P3-APPR-12 | PASS | HTTP, Automated | 8 | was BLOCKED on `bdbf061`; T12 route served now |
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
| P3-DUP-08 | PASS | HTTP, Automated | 6 | was BLOCKED on `bdbf061`; T12 route served now |
| P3-DUP-09 | PASS | HTTP, Automated | 5 |  |
| P3-DUP-10 | PASS | HTTP, Automated | 4 |  |
| P3-DUP-11 | PASS | HTTP | 7 |  |
| P3-DUP-12 | PASS | HTTP | 3 |  |
| P3-DUP-13 | PASS | HTTP | 2 |  |
| P3-HOOK-01 | PASS | HTTP | 4 |  |
| P3-HOOK-02 | PASS | HTTP | 2 |  |
| P3-HOOK-03 | PASS | HTTP | 6 |  |
| P3-HOOK-04 | PASS | HTTP, Automated | 3 |  |
| P3-HOOK-15 | PASS | HTTP, Automated | 4 |  |
| P3-HOOK-05 | PASS | HTTP, Automated | 4 | rerun after a harness correction (see below) |
| P3-HOOK-06 | PASS | HTTP, Automated | 2 |  |
| P3-HOOK-07 | PASS | HTTP, Automated | 5 |  |
| P3-HOOK-08 | PASS | HTTP, Automated | 4 |  |
| P3-HOOK-09 | PASS | HTTP | 3 |  |
| P3-HOOK-10 | PASS | HTTP | 2 |  |
| P3-HOOK-11 | PASS | HTTP, Automated | 2 |  |
| P3-HOOK-12 | PASS | In process, Automated | 4 |  |
| P3-HOOK-13 | PASS | Database, Automated | 5 |  |
| P3-HOOK-14 | PASS | HTTP, Automated | 5 | rerun after a harness correction (see below) |
| P3-SEC-01 | PASS | HTTP | 4 |  |
| P3-SEC-02 | PASS | HTTP | 7 |  |
| P3-SEC-03 | PASS | HTTP | 4 |  |
| P3-SEC-04 | PASS | HTTP | 2 |  |
| P3-SEC-05 | PASS | HTTP, Automated | 6 |  |
| P3-SEC-06 | PASS | HTTP, Automated | 4 |  |
| P3-SEC-07 | BLOCKED | HTTP, Browser | 8 | metrics half: no metrics endpoint is exposed by design; logs, audit, events, proxy log and screens pass |
| P3-SEC-08 | PASS | Browser | 10 | was BLOCKED on `bdbf061`; integration screen mounted now |
| P3-SEC-09 | PASS | In process | 2 |  |
| P3-SEC-10 | PASS | HTTP | 2 |  |
| P3-SEC-11 | BLOCKED | HTTP, In process | 2 | no metrics endpoint by design; tag rules covered in process by `WebhookTelemetryTest` |
| P3-KILL-01 | PASS | HTTP, Automated | 5 |  |
| P3-KILL-02 | PASS | HTTP, Automated | 5 |  |
| P3-KILL-03 | PASS | HTTP, Browser, Automated | 11 | was BLOCKED on `bdbf061`; integration screen mounted now |
| P3-KILL-04 | PASS | HTTP, Automated | 5 |  |
| P3-KILL-05 | PASS | HTTP, Automated | 18 |  |
| P3-KILL-06 | PASS | HTTP, Automated | 4 |  |
| P3-KILL-07 | FAIL | HTTP, In process, Automated | 8 | was BLOCKED; finding #293: refused, nothing written, but the 500 is the default error body, not a problem body |
| P3-KILL-08 | PASS | HTTP, Automated | 3 |  |
| P3-KILL-09 | PASS | HTTP, Automated | 3 |  |
| P3-KILL-10 | PASS | HTTP | 3 |  |

[#140]: https://github.com/stevdrey/dokene/issues/140
[#293]: https://github.com/stevdrey/dokene/issues/293
