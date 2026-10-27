# Suite #149: Phase 3 abuse cases on the integrated stack

Executable form of the case matrix in `docs/verification/phase-3-security-cases.md`. Every script records one line
per check in `out/results.tsv` (`case id`, `channel`, `PASS|FAIL|BLOCKED`, `time`, `note`) and prints the same line.
All values are synthetic: the Meta ids, phone numbers, tokens and the three canaries exist only to be searched for.

## What is in here

| File | Role |
| --- | --- |
| `GraphStub.java` | Stand-in for the Graph API on `127.0.0.1:18080` inside the backend's network namespace. Behaviour is set through files in `stub/` (`send`, `templates`, `phones`, `delay.ms`); it logs every request with the bearer it saw as `A`, `B`, `other` or `none`, never the value. |
| `stack149.sh` | Starts the stub, writes `out/compose.qa149.yaml` (local secret source with the canaries, stub base URL, short inbox and park windows, switches and limits from `QA149_OUTBOUND`, `QA149_RATE`, `QA149_TEMPLATE_MAX_AGE`) and restarts the backend with it. |
| `fixtures149.sh` | One-off: creates the four extra Keycloak users, tenant B, the memberships, both integrations (proven against the stub) and both template mappings; writes `out/ids`. |
| `env149.sh` | Prelude every suite sources: `.env`, the helpers, seven logins, `out/ids`, and a fixture check that re-proves the integrations and re-maps the templates when an earlier run left them changed. |
| `qa149.sh` | Helpers: `req` (BFF call with CSRF and tenant header), problem body checks, `psqlq`, stub control, snapshots of the row counts, the recorder (`rec`, `chk`, `summary`) and API fixtures (`mk_customer`, `ready_approved`, `hook_post`, …). |
| `sec149-<group>.sh` | One suite per matrix group: `perm`, `idor`, `rls`, `csrf`, `appr`, `tmpl`, `cons`, `dup`, `hook`, `kill`, `sec`. |
| `sec149-browser.sh` + `.mjs` | P3-CSRF-03, P3-SEC-08 and the screen halves of P3-SEC-07 and P3-KILL-03 in a headless Chromium. |
| `sec149-inprocess.sh` | P3-PERM-13, P3-HOOK-12, P3-SEC-09, P3-SEC-11, P3-KILL-07 and every automated fixture the matrix names, run as backend tests on the same commit. |
| `run-all.sh` | The whole thing in order, into `out/`. |
| `isolated149.sh` + `compose.isolated149.yaml` | Optional prelude for a second stack beside a standard one: project `dokene-qa149`, own image tags, host ports 28080/28081/25173/25432, and a generated `.curlrc` (plus a mapping proxy for Chromium) so every URL still reads `localhost:8080/8081/5173`. |

## Running

From the repository root with the dev stack up (`docker compose up -d`) and images built from the commit under test:

```sh
bash docs/verification/issue-149-evidence/scripts/fixtures149.sh      # once per database
QA149_PW_DIR=/path/with/node_modules/playwright-core \
bash docs/verification/issue-149-evidence/scripts/run-all.sh          # about 30 minutes
```

`run-all.sh` prints a per-suite line and writes `out/results.tsv`, `out/verdicts.tsv` (worst result per case id) and
`out/sec149-*.out`. A single suite runs with `bash docs/verification/issue-149-evidence/scripts/sec149-perm.sh`;
`RESULTS=<file>` sends its lines elsewhere. `out/` is not committed; the recorded run is `../outputs.txt`.

To run beside a standard dev stack without touching it, source `isolated149.sh` first in every shell, then
`docker compose up -d --build` (the `:qa149` images), `scripts/seed-local-qa.sh`, `fixtures149.sh` and `run-all.sh`.

The browser cases need `playwright-core` 1.52 installed somewhere outside the repository (`QA149_PW_DIR`) and a
Chromium headless shell (`QA149_CHROME`, defaults to the Playwright cache path on macOS; on Linux point it at
`~/.cache/ms-playwright/chromium_headless_shell-<rev>/chrome-headless-shell-linux64/chrome-headless-shell`). The in-process suite needs
Docker for Testcontainers and the JDK the Gradle build declares.

## Things that bite

- **Any plain `docker compose up -d <service>` recreates the backend without the override** and the suite loses the
  stub and the canaries. Use `stack149.sh restart` (or `stack149.sh frontend` for the proxy image) while the suite
  runs, and `stack149.sh down` to go back to the plain stack.
- Backend restarts invalidate sessions; the suites that restart (`dup`, `kill`) log everyone in again themselves.
- `P3-DUP-12` kills the backend mid-send and waits up to three minutes for the recovery processor; `P3-HOOK-05`
  waits for the one-minute park window. Those two account for most of the running time.
- The tenant send rate counts attempt rows started in the last minute across restarts, so `P3-DUP-13` waits for the
  earlier attempts to leave the window before it starts.
- A provider `5xx` is an *unknown outcome* (ADR 0027), not a transient failure. The transient stub mode is `rate429`.
- Nothing here touches product code. If a case cannot be driven on the commit under test the script records
  `BLOCKED` with the reason instead.
