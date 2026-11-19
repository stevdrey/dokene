# Issue #328 Private temporary files in resilience verification: verification record

Status: single PR, part of epic [#305](https://github.com/stevdrey/dokene/issues/305). It closes [#328](https://github.com/stevdrey/dokene/issues/328): the RES-01 configuration matrix of suite #123 (`docs/verification/issue-123-evidence/scripts/res01.sh`) copied the real provider key from `.env` into a Compose override file written with the shell's default permissions at a fixed path, and removed that file only when the script reached its last line. An interrupted or failed run could leave the key on disk. [Security invariant 40](../security/security-invariants.md) now covers such files.

This record is sanitized. Every key used in the checks below was synthetic, generated for the run and discarded with its temporary directory. No real credential was read, and no exploit detail is recorded.

## Environment

- **Run:** 2026-11-19, branch `328-security-protect-temporary-files-in-resilience-verification`, based on `main` at `9698592`.
- **Toolchain:** Linux 7.3 (Fedora 45), GNU bash 5.3.15, GNU coreutils `mktemp`/`stat`, ShellCheck 0.11.0.
- **Not run:** `res01.sh` itself against a stack. It `cd`s into a separate QA checkout, sources env files that are not in the repository and calls the real OpenAI API for cases 4b, 6 and 7. `./gradlew check` and the frontend suite were not run, because no backend or frontend file changed.

## Recheck of the finding

The analyzed locations still matched `main` at `9698592`:

| Location | State before this change |
| --- | --- |
| `res01.sh:3` (root control) | override at a fixed path in the QA working folder (`OV=…/qa123/ov.yaml`) |
| `res01.sh:5` (`recreate`) | `printf … > $OV`: the file took the shell's umask, normally readable by other local users |
| `res01.sh:13`, cases 4b and 7 (entrypoint) | the key read from `.env` into a shell variable and written into the override |
| `res01.sh:27` (sink) | `rm -f $OV` only on the last line: no cleanup on an error, `exit` or interrupt |
| `base.sh` (propagation) | sources the suite #121 env helper; sets no trap and writes no override |

No other evidence script under `docs/verification/` writes a real key into a file: the provider failure scripts of suite #123 write overrides through `scripts/lib/provider-test-double.sh`, which always uses the synthetic key (issue 329).

## What changed

- **The key is no longer written.** `compose.yaml` already passes `DOKENE_AI_OPENAI_API_KEY: ${DOKENE_AI_OPENAI_API_KEY:-}` to the backend, read from `.env`. Cases 4b and 7 now leave the key out of the override, so the backend keeps that same key; the script no longer reads `.env`. Cases 5 and 6 still set an empty and a deliberately invalid key, as before.
- **New helper `scripts/lib/private-temp.sh`.**
  - `private_temp_dir VAR` creates a directory under `$TMPDIR` or `/tmp` with mode 700 and removes it, with everything in it, on the shell's EXIT trap and on HUP, INT and TERM. After a signal it raises the same signal again, so the script ends the way that signal ends it. It refuses, creating nothing, when another EXIT trap is already set, rather than replace it.
  - `private_temp_write FILE` writes standard input to the file under umask 077 (mode 600), replacing any previous file.
- **`res01.sh`** loads the helper from its own location before its first `cd` (the same loader form as #401), keeps the override in a private directory and writes it with `private_temp_write`. Its probes, cases and output format are unchanged.
- **`scripts/tests/private-temp-test.sh`**, run by the new CI job `private-temp` in `.github/workflows/scripts.yml`.
- The suite #123 scripts README and the security invariants are updated.

## Checks

| Check | Kind | Outcome |
| --- | --- | --- |
| `./scripts/tests/private-temp-test.sh` | automated, offline | all tests passed (42 checks) |
| — directory and file modes with `umask 000` | automated, offline | directory 700, written and replaced files 600 |
| — cleanup on a normal end, `exit 3` and a `set -e` failure | automated, offline | directory removed, exit status kept (0, 3, 1) |
| — cleanup on HUP, INT and TERM sent to a running script | automated, offline | directory removed, the script ends by the signal (status 129, 130, 143) |
| — refusals (an existing EXIT trap, an invalid variable name) | automated, offline | status 2, no directory created |
| — RES-01 as written: `recreate()` and every case line, run with a stub `docker` in a folder whose `.env` and environment hold a synthetic key | automated, offline | 8 overrides, each mode 600 in a mode 700 directory; none holds the synthetic key or a `$`; only cases 5 and 6 set a key; the directory is gone afterwards |
| Mutation: `res01.sh` reverted to `main` | automated, offline | 6 failures, including `no RES-01 override holds the synthetic key` and the mode check |
| `./scripts/tests/provider-test-double-test.sh`, `bff-login-test.sh`, `dev-env-smoke.sh` | automated, offline | all passed (the first two scan `docs/verification/`) |
| `shellcheck` on the helper and the test | automated | clean |
| `bash -n` on `res01.sh` | automated | parses |

## Limitations

- `res01.sh` was not run end to end; the test runs its override writer and case lines as written, not its probes, env files or the stack.
- After an interrupt the override is removed but the backend is not recreated without it: it keeps the case's environment until the operator restores it, as before. That environment never holds a key the stack did not already have.
- `SIGKILL` and a power loss cannot run any cleanup. The directory is then left behind, still private (mode 700); since the key is no longer written, it holds no credential.
- The original outputs in `../outputs.txt` were produced before this change and are kept unchanged.
