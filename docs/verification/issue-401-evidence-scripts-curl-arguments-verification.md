# Issue #401 Session values out of curl arguments in evidence scripts: verification record

Status: single PR, follow-up of [#327](https://github.com/stevdrey/dokene/issues/327) (epic [#305](https://github.com/stevdrey/dokene/issues/305)). It closes [#401](https://github.com/stevdrey/dokene/issues/401): historical evidence scripts under `docs/verification/` still passed a session's `X-CSRF-TOKEN` header, and in one case a login callback URL with a code, to `curl` as arguments, where any local user can read them in the process table while curl runs. [Security invariant 35](../security/security-invariants.md) no longer has an exception for these scripts.

This record is sanitized. Every value used in the checks below was synthetic, generated for the run and discarded with its temporary directory.

## Environment

- **Run:** 2026-11-16, branch `401-security-keep-session-values-out-of-curl-arguments-in-the-remaining-evidence-scripts`, based on `main` at `04cfe3c`.
- **Toolchain:** Linux 7.3 (Fedora 45), GNU bash 5.3.15, curl 8.21.0 (only as the name the stub replaces), ShellCheck 0.11.0.
- **Not run:** the evidence scripts themselves. They `cd` into a separate QA checkout and source env files that are not in the repository, against a disposable local stack. `./gradlew check` and the frontend suite were not run, because no backend or frontend file changed.

## What changed

Each script below now loads `scripts/lib/curl-protected.sh` as its first command, before any `cd`:

```bash
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)/scripts/lib/curl-protected.sh" || { echo "scripts/lib/curl-protected.sh not found" >&2; exit 1; }
```

The path is resolved from the script's own location (`../../..` for `issue-246-evidence/res09-retest.sh`, which has no `scripts/` folder), so the later `cd` does not affect it. Once loaded, `curl` is the wrapper function from #327: every `-H` value, cookie value holding `=` and URL reaches the real curl in a `-K` config on a file descriptor. The function is inherited by the helpers of the sourced env files and by the background subshells (`( curl … ) &`) the scripts use. No script or env file in the repository redefines `curl` or calls it through `command`, `env`, `xargs` or `timeout`.

| Scripts | Session values that were curl arguments |
| --- | --- |
| `issue-118-evidence/scripts/fx-ui-extra.sh`, `sec121-h-relogin.sh` | CSRF header, BFF URLs |
| `issue-120-evidence/scripts/ai120-b.sh` | CSRF header. It already sourced `qa.sh`, which loads the wrapper, but a sourced loader only returns on failure and the script would go on with the real curl. |
| `issue-121-evidence/scripts/sec121-h.sh`, `sec121-i.sh`, `sec121-l.sh`, `sec121-m.sh` | CSRF header |
| `issue-123-evidence/scripts/res05.sh`, `res06.sh`, `res08.sh` | CSRF header |
| `issue-123-evidence/scripts/res09.sh` | login callback URL with a `code` parameter. Not listed in the Issue: the code is a deliberately bogus value, but the script matches the same rule, so it is brought in line too. |
| `issue-149-evidence/scripts/sec149-cons.sh`, `sec149-dup.sh` | CSRF header |
| `issue-246-253-evidence/scripts/ret246.sh`, `issue-246-evidence/res09-retest.sh` | CSRF header |

The other curl calls under `docs/verification/` send only cookie jar files, or go through the `api` helpers of `qa.sh` and `qa149.sh`, which already load the wrapper (#327).

`scripts/tests/bff-login-test.sh` (CI job `bff-login-helper`) gains two groups of checks:

- **Representative helpers.** Seven helper functions are taken from the scripts as written (the lines up to where `bash -n` parses them) and run after the script's own loader line, with an argv-recording curl stub as the only curl and the synthetic token `tok-5281`: `mkt` and `bg_call` (`sec121-i.sh`, the second in a background subshell), `prov` (`sec121-l.sh`, token read in a nested command substitution), `call` (`sec121-m.sh`), `hit` (`ret246.sh`, CSRF header from an optional expansion), `call` (`res09-retest.sh`, caller-supplied curl arguments) and `code` (`res09.sh`). A helper or loader line that cannot be found is a failure, and the recordings are cleared before each run, so a check never passes on another helper's calls. Each check asserts the number of curl calls, that every call reads a `-K` config, that no token or URL is on curl's argv, and that the URL and CSRF header reach curl in the config.
- **Scan.** Every `*.sh` under `docs/verification/` that sends a CSRF header from a variable (header name in any case, as in the existing scan of `scripts/`, which now ignores case too), a `JSESSIONID=` value or a callback `code` (20 scripts today, including the sourced `qa.sh`, `qa149.sh` and `sec121-a.sh` from #327) must have the own-path loader, before its first `cd` or `curl`; the loader, run with the script's path, must define the wrapper; and run with a path where the wrapper is absent, it must print `scripts/lib/curl-protected.sh not found` and stop.

## Checks

| Check | Kind | Outcome |
| --- | --- | --- |
| `bash -n` on the 15 changed scripts | automated | all parse |
| `./scripts/tests/bff-login-test.sh` | automated, offline | all tests passed, including the 34 new helper checks and the 5 scan checks |
| Mutation: loader line removed from `sec121-i.sh` | automated, offline | 9 failures: for each of the two helpers of that script, the missing loader and its three checks, and the scan names the script as missing |
| Mutation: loader line of `res08.sh` moved after its `cd` | automated, offline | the scan names it as late |
| Tree restored after the mutations | automated, offline | all tests passed |
| Probe from independent review: helper `bg_call` renamed in `sec121-i.sh` | automated, offline | fails (`the loader and helper are found in …`, plus the three `bg_call` checks); before the fix the checks passed on the previous helper's recordings |
| Probe from independent review: new script sending `-H "x-csrf-token: $tok"` without the loader | automated, offline | the scan names it as missing; before the fix the lowercase header was not detected |
| `shellcheck scripts/tests/bff-login-test.sh` | automated | only the info note SC1091 that `main` already has |

## Limitations

- The evidence scripts were not run end to end; the checks above exercise their loader lines and helpers as written, not their env files or the stack.
- The helper checks replace `jq` and the env-file helpers (`api`, `etag`, `chk`, `leak`) with stand-ins, so they show where curl's arguments go, not what the BFF answers.
- The residual exposure is unchanged from #327: the config is readable through the file descriptor by the same user and root while curl runs.
