# Issue #327 Credentials out of process arguments: verification record

Status: single PR, part of epic [#305](https://github.com/stevdrey/dokene/issues/305). It closes [#327](https://github.com/stevdrey/dokene/issues/327): some local setup and verification commands passed a password, key or token to another program as a command line argument, where any local user who can list processes could read it while the program ran. The rule now holds as [security invariant 35](../security/security-invariants.md).

This record is sanitized: it gives outcomes, counts and settings only. It contains no credential, key, token or exploit detail. Every value used in the checks below was synthetic, generated for the run and discarded with its throwaway container or directory.

## Environment

- **Run:** 2026-11-13, branch `327-security-keep-credentials-out-of-process-arguments`, based on `main` at `3a27773`. The analysed revision of the Issue was `a328848`; every analysed location was rechecked on `3a27773` and still applied, except where noted below.
- **Tested revision:** the working tree that became the first commit of this branch on top of `3a27773` (a commit cannot name its own hash). Git blob ids of the main files under test: `infra/docker/postgres/init/01-create-database-roles.sh` `3ad3a6cd09d0758500d9441b5c76ffb1a1c4c863`, `scripts/dev-env.sh` `e39ad880e16ecd0aabcea666998e086864ed80a4`, `scripts/keycloak-apply-realm-settings.sh` `c9fb6dbefae8c0fbf7f7e17d7a0f37391be494c7`, `scripts/verify-keycloak-bff-e2e.sh` `f159500367d3641d938f781427064bf33f34118e`. Every check in the table ran on that tree (`806709a`), except the `sec121-j.sh` CSRF row. That row ran on the second commit's tree, where the only script change is `docs/verification/issue-121-evidence/scripts/sec121-j.sh`, blob `c4b2d94e2ea4613b8d7140429437472b5b6b8de4`; the other blob ids above are unchanged there. The review fixes below changed more files; their checks ran on the working tree that became the fourth commit, named in that section.
- **Toolchain:** Linux 7.3 (Fedora 45), Docker 29.9.3, Docker Compose 5.7.1, `postgres:17-alpine` at the digest pinned in `compose.yaml` (psql 17.12), the local Keycloak image (26.8.2), curl 8.21.0, jq 1.8.2, ShellCheck from the distribution.
- **Not run:** `./gradlew check` and the frontend suite, because no backend or frontend file changed. The full local stack was not started; see Limitations.

## What changed

| Location | Before | After |
| --- | --- | --- |
| `infra/docker/postgres/init/01-create-database-roles.sh` | role passwords passed to `psql` as `--set=…_password=<value>` | `psql` reads them with `\getenv` from its own environment and quotes them with `:'name'`; the script fails closed when either is unset |
| `scripts/dev-env.sh` `env_set` (writes generated secrets to `.env`) | value passed as `awk -v v=<value>` | value passed in the environment of that one `awk` (`ENVIRON`); backslashes are now kept instead of interpreted |
| `scripts/keycloak-apply-realm-settings.sh` | admin password and new test user passwords passed to `kcadm.sh` as `--password` and `--new-password` inside the container | both passed as `KC_CLI_PASSWORD`, which `kcadm.sh` reads for login and for `set-password` |
| `scripts/verify-keycloak-bff-e2e.sh` | client secret used as a `grep` pattern argument and printed in failure messages | pattern given as a file (`grep -F -f <(…)`); failure messages name it as `<OIDC client secret>` |
| `scripts/verify-issue-59-followup.sh`, `scripts/verify-issue-59-security.sh` | test user passwords as `grep -e` arguments | pattern file |
| Evidence scripts of issues 119, 120, 121, 123 and 149 under `docs/verification/` | `curl --data-urlencode password=<value>`, `docker compose exec -e PGPASSWORD=<value>`, `jq --arg p <value>`, Keycloak user JSON in `curl -d`, bearer token in `curl -H`, provider key, database password and a test session CSRF token as `grep` arguments | password on curl's stdin (`password@-`), `PGPASSWORD=… docker compose exec -e PGPASSWORD` (by name), `$ENV.QA_PW` in jq, JSON on stdin (`--data-binary @-`), header from a file descriptor (`-H @<(…)`), pattern files |

The location `scripts/verify-keycloak-bff-e2e.sh:143-150` of the analysed revision had already been fixed on `main` by issue 382 (`submit_credentials` posts the password on stdin); the change here covers the remaining secret in that script. `docs/verification/issue-121-evidence/scripts/env121.sh` only loads `.env` into the environment and needed no change.

## Checks

| Check | Kind | Outcome |
| --- | --- | --- |
| `scripts/tests/dev-env-smoke.sh`: `env_set` called through a shadowing `awk` that records every argument list; value with a quote, a backslash and `$` | automated | No argument list held the value; `.env` held it byte for byte, one line per key, other keys untouched. The same test fails on `3a27773` |
| `scripts/tests/dev-env-smoke.sh`: init script run against a stub `psql` that records its arguments and standard input | automated | Neither held a password; standard input named both by variable (`\getenv`); an unset password stops the script. The same test fails on `3a27773` |
| `scripts/tests/keycloak-apply-realm-settings-test.sh`: the fake in-container `kcadm.sh` now logs its own arguments and accepts the admin login only through `KC_CLI_PASSWORD` | automated | No admin or test user password on the host or in-container command lines, no `--password`, `--new-password` or `-p`; each user still gets its own password; every failure path still passes. On `3a27773` the login and password tests fail |
| `scripts/tests/bff-login-test.sh`, `scripts/tests/verify-journey-follow-up-disposition-test.sh`, ShellCheck gate in the smoke test, `bash -n` on every changed script | automated | Pass |
| Init script in a throwaway `postgres:17-alpine` container with the `compose.yaml` restrictions (uid 70, read-only root, no capabilities, `no-new-privileges`, the same tmpfs mounts), passwords containing `'`, `\` and `$` | live, throwaway | Both roles created, not superuser, no `BYPASSRLS`; each logs in over SCRAM with its exact value and is refused with any other; no value in the container log. The first run connected over loopback, where the image trusts every connection, so the final run authenticated over the container address |
| `docker exec -e NAME` and `docker compose exec -e NAME` with the value only in the caller's environment | live | The container received the value |
| Evidence helpers of `qa149.sh` (`qa_login`, `rt`, `runctx`, `kc_token`, `kc_create_user`) and `qa_login` of `issue-121-evidence/scripts/qa.sh`, run against recording stubs of `curl`, `docker` and `jq` | simulated | 16 recorded command lines, none with a synthetic password, key or token; the stubs received each value on stdin or in their environment. The first pass found the user JSON in `curl -d` and the bearer token in `-H`; both were moved to stdin and a header file and the pass repeated |
| `sec121-j.sh` CSRF check rewritten to read the token from `curl … \| jq` as a `grep -f` pattern file, after the independent verifier flagged it as optional finding F1 in round 1 | automated, ad hoc | `bash -n` passes; the pattern file form matches the token literally and ignores a look-alike. With no token, nothing matches, where the old empty argument matched every line |
| Real `curl` against a loopback listener: `-H @<(…)` with `--data-binary @-`, and `--data-urlencode "password@-"` with `&`, `=`, space, `+`, `%` and a non-ASCII letter | live, loopback | Header and body arrived intact; the form value decoded to the exact input |
| `grep -F -f <(…)` and `has_literal` with a value holding regular expression characters | automated, ad hoc | Literal match only; a look-alike line does not match |

## Review fixes

A code review of the pull request found seven issues. The fixes, made in the fourth commit, were checked on the working tree that became that commit:

| Finding | Fix | Check | Outcome |
| --- | --- | --- | --- |
| `verify-keycloak-bff-e2e.sh` compared leaks against a placeholder, because it read `DOKENE_OIDC_CLIENT_SECRET`, a name only the oidc-bridge container has | Reads `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET` from `.env` (container name as fallback) and stops when neither is set | Ad hoc, synthetic `.env` value | The real value is matched in a header file and a piped body; without it the script stops naming the variable; the fallback still works |
| A match piped into `grep -q` under `pipefail` could be lost to SIGPIPE | New `scripts/lib/secret-match.sh`: `contains_literal` counts matches, so grep reads all its input. GNU grep also stops early when its output is `/dev/null`, so discarding the output is not enough | New smoke check | A match at the start of a 2 MiB piped payload is found under `pipefail` |
| The `grep -F -f <(…)` form was repeated in three scripts | All three use `contains_literal` or `grep_literals`; empty values are skipped, because an empty pattern matches every line | New smoke check, a shadowing `grep` records argv | Literal match only, empty values skipped, no value on any recorded argv |
| The fail-closed init test depended on the caller's environment | Clears `DOKENE_DB_MIGRATION_PASSWORD` itself | Smoke run with the variable exported by the caller | Passes |
| The stubbed Keycloak test never ran `kcadm()`'s admin login | The docker stub runs the container script for every call, and a new check refuses a wrong admin password | Keycloak test; negative control with `--password` put back into the login | Passes; the negative control fails 11 checks |
| The admin login was copied into both container scripts of `keycloak-apply-realm-settings.sh` | One shared snippet (`KCADM_LOGIN`) starts both | Keycloak test, which now runs that snippet on every call | Passes |
| The redirect failure message no longer showed the URL | Shows it again with the client secret replaced and every query and fragment parameter value shown as `<redacted>`, so it names where a value leaked without printing a token (an optional finding of the fourth verification round, fixed in the fifth commit) | Ad hoc, synthetic values | Parameter names and the path are kept, and no value is printed |

## Limitations

- The exposure was not demonstrated on a shared host, as the Issue notes; the checks prove that the values no longer reach an argument list, not that an attacker could have read them before.
- The full local stack was not started. The Keycloak path was checked with the repository's stubbed test and the `kcadm.sh` help of the pinned image, which documents `KC_CLI_PASSWORD` for both `config credentials` and `set-password`; the BFF end-to-end and issue 59 scripts were checked statically and through their helpers only.
- The evidence scripts under `docs/verification/` record past runs; several start with paths of the machine they ran on. They were changed so that a rerun does not leak, and verified through their helpers, not rerun end to end.
- A value handed through the environment is still readable by the same user and root (`/proc/<pid>/environ`, `docker inspect` for container environment). That is the protected channel this Issue asks for; the container environment of the long-running services is unchanged and covered by issue 308.
