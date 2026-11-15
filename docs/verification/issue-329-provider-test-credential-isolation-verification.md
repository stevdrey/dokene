# Issue #329 Credentials in provider failure tests: verification record

Status: single PR, part of epic [#305](https://github.com/stevdrey/dokene/issues/305). It closes [#329](https://github.com/stevdrey/dokene/issues/329): the provider failure scripts of the resilience suite pointed the backend at a fault-injection stub, a refused port or a blackholed address through a Compose override that set only the provider and the base URL, so the backend kept the OpenAI key configured in `.env` and sent it to each of those destinations. The rule now holds as [security invariant 37](../security/security-invariants.md).

This record is sanitized: it gives outcomes, counts and settings only. It contains no credential, key, request body or exploit detail. Every key used below was synthetic: the fixed test key of the library, or a real-looking value generated for the run and discarded with its temporary directory. No real provider key was read, and no OpenAI call was made.

## Environment

- **Run:** 2026-11-14, branch `329-security-isolate-credentials-in-provider-failure-tests`, based on `main` at `dc0d27a`. The analysed revision of the Issue was `a328848`; every analysed location was rechecked on `dc0d27a`, with the result below.
- **Tested revision:** the working tree that became the first commit of this branch on top of `dc0d27a` (a commit cannot name its own hash). Every check in the table ran on that tree.
- **Toolchain:** Linux 7.3 (Fedora 45), GNU bash 5, ShellCheck 0.11.0, curl 8.21.0, OpenJDK 27 compiling the stub with `javac --release 26`, Node `--check` for the `.mjs` script.
- **Not run:** `./gradlew check` and the frontend suite, because no backend or frontend file changed. The local stack was not started and the changed evidence scripts were not rerun end to end; see Limitations.

## Analysed locations on `dc0d27a`

| Location | Finding on `dc0d27a` |
| --- | --- |
| `docs/verification/issue-123-evidence/scripts/res04b.sh:7-8` (root control) | Still applied: `recreate()` wrote `DOKENE_AI_PROVIDER` and `DOKENE_AI_OPENAI_BASE_URL` only, so the key from `.env` stayed in force |
| `res04b.sh:12-13` (entrypoint) | Still applied for the refused port `127.0.0.1:9`. The blackholed address `10.255.255.1` is no longer reachable from the backend: since issue 322 the endpoint policy rejects that base URL at startup, before a client is built |
| `res0234.sh:7-9` (propagation) | Still applied: the suite ran against whatever backend was up, with no check of its key |
| `compose.yaml` backend environment (propagation) | Unchanged and correct for normal use: `DOKENE_AI_OPENAI_API_KEY` comes from the shell or `.env`. The fix belongs in the test harness, which is the one place that changes the destination |
| `OpenAiConfiguration.java:22-32`, `OpenAiResponsesApiAdapter.java:177-181` (sinks) | Unchanged: the client sends the configured key to the configured base URL, which is its job. The endpoint policy accepts any loopback port over HTTP for test servers and cannot tell a stub from a local gateway that forwards the key, so no backend change was made |
| `OpenAiResponsesApiAdapterTest.java:178` (expected control) | Unchanged: the adapter tests already use the synthetic `test-api-key` against an in-process loopback server; the scripts now follow the same pattern |

The same pattern was found in four stub-phase drivers that exported `DOKENE_AI_OPENAI_BASE_URL` before a `dev-env.sh` restart (`phase-3-evidence/scripts/regress.sh`, `rerun-ui.sh`, `issue-118-evidence/scripts/drive-ui.sh`, `drive-security-stub-phase.sh`) and in the other stub cases of suite #123 (`res05.sh`, `res06.sh`, `res07.mjs`, `res10.sh`). They are covered by the same change. The stub itself never logged the `Authorization` header, so no recorded output under `docs/verification/` holds a key.

## What changed

| Location | Before | After |
| --- | --- | --- |
| New `scripts/lib/provider-test-double.sh` | — | `provider_test_override` and `provider_test_export` accept only an http(s) loopback URL, always set the synthetic key `PROVIDER_TEST_API_KEY`, and refuse extra environment that would replace the provider, base URL or key. `provider_test_assert_backend` reads the running backend's environment through a pipe and, unless it holds a loopback base URL and exactly the synthetic key, stops the backend and returns 3. Messages name variables, never values |
| `res04b.sh` | override with provider and base URL only; blackholed address case | override through the library and a backend check after each recreate (except the case that must fail to start); the blackholed case is replaced by a check that a non-loopback destination is refused |
| `res0234.sh`, `res05.sh`, `res06.sh`, `res10.sh`, `res07.mjs` | ran against whatever backend was up | check the backend first, and `res07.mjs` again after it restarts the backend |
| The four stub-phase drivers | `export DOKENE_AI_OPENAI_BASE_URL=…` | `provider_test_export`, a backend check after each stub start, `provider_test_unexport` before going back to the real provider |
| `issue-121-evidence/scripts/Stub.java` | answered any request | answers any `Authorization` other than the synthetic key with HTTP 401, logs `credential=unexpected` without the value and does not store the body; `STUB_DIR` moves its control folder |
| New `scripts/tests/provider-test-double-test.sh`, job `provider-test-double` in `scripts.yml` | — | offline tests with a stub `docker`; the workflow now also runs on changes under `docs/verification/*-evidence/scripts/`; both new files are in the ShellCheck gate of `dev-env-smoke.sh` |
| READMEs of suites #121, #122, #123; security invariant 37 | — | document the library, the stub behaviour and the rule |

## Checks

| Check | Kind | Outcome |
| --- | --- | --- |
| `scripts/tests/provider-test-double-test.sh` | automated | 49 checks pass: loopback destinations accepted, `api.openai.com`, a private address, look-alike hosts, userinfo, a query and an embedded newline refused; the override and export carry only the synthetic key even when the caller's environment holds a real-looking one; a refused call leaves the previous override untouched; the backend check passes for a loopback stub with the synthetic key and fails closed (status 3, backend stopped) for a real-looking key, the default OpenAI endpoint, a private address, a blank key and no running backend; no recorded `docker` argument list or message held the real-looking key; every changed script calls the check; no evidence script sets a stub base URL by hand |
| Negative control: the library writes the caller's key into the override and skips the key comparison | automated | 6 checks fail, among them "the override sets the synthetic key", "the caller's key never reaches the override" and "a real-looking key on the test double fails closed" |
| `Stub.java` compiled and run on `127.0.0.1:18080` with a temporary `STUB_DIR`; requests sent with real curl, each header from a file descriptor | live, loopback | synthetic key: HTTP 200; a real-looking generated key: HTTP 401; no header: HTTP 401. Both refusals are logged as `credential=unexpected`; the refused body was not stored; neither the stub's files nor its output contain the generated key |
| ShellCheck 0.11.0 on both new files; `bash -n` on every changed shell script; `node --check` on `res07.mjs` | automated | Pass |
| `scripts/tests/dev-env-smoke.sh` (ShellCheck gate with the two new targets) | automated | Pass, "shell scripts are ShellCheck-clean" included |

## Spring relaxed binding (second commit)

The independent verifier of round 1 reported optional finding F1: the override filter and the backend check compared two exact variable names, while Spring also binds other spellings of the same property (`DOKENE_AI_OPENAI_APIKEY`, lower case names, doubled separators) and can load configuration from `SPRING_APPLICATION_JSON`, `SPRING_CONFIG_*` or `-D` options in JVM option variables. A hand-written override could have set a second spelling next to the synthetic key. Nothing in the repository did. The fix is in the second commit:

- `provider_test_override` accepts extra environment only as one `      NAME: "value"` line per upper-case variable. It refuses a name that, with case and separators ignored, mentions `OPENAI`, `APIKEY`, `BASEURL` or `TRUSTEDHOSTS`, starts with `DOKENEAIPROVIDER`, `SPRINGAPPLICATIONJSON` or `SPRINGCONFIG`, or is a JVM option variable.
- `provider_test_assert_backend` reads every environment entry and mount of the backend:
  - Every spelling of the key must hold the synthetic key, and every spelling of the base URL must be a loopback URL.
  - Each canonical name must appear exactly once.
  - It fails closed on a set `SPRING_APPLICATION_JSON` or `SPRING_CONFIG_*`, on JVM options that set a `dokene.ai` or configuration property, and on a mounted secret file named for the provider.
  - The image's own `JAVA_TOOL_OPTIONS` and the Spring security settings still pass.

Checks on the working tree that became the second commit:

| Check | Kind | Outcome |
| --- | --- | --- |
| `scripts/tests/provider-test-double-test.sh` | automated | 82 checks pass. The new ones cover 13 refused alias and configuration names, 5 malformed extra lines, a real-looking key under three key spellings, a base URL alias on the real endpoint, `SPRING_APPLICATION_JSON`, `SPRING_CONFIG_*`, `JDK_JAVA_OPTIONS` with a `-D` key, a mounted key file and a duplicated canonical key. All of them fail closed, and the backend's own secret mount and an alias that holds the synthetic key pass |
| Negative control: the new tests run against the first commit's library | automated | 30 checks fail, one for each alias case |
| The `docker inspect` template run against a local container, only the line prefixes counted | live, local | environment entries and mounts are listed as `ENV` and `MOUNT` lines; the trailing blank line is ignored |
| ShellCheck 0.11.0 (`-x`) on both files; `scripts/tests/dev-env-smoke.sh` | automated | Pass |

## Code review fixes (third commit)

A code review of the pull request found eight non-blocking issues. They were fixed in the third commit and checked on the working tree that became that commit:

| Finding | Fix | Check | Outcome |
| --- | --- | --- | --- |
| Name normalization used `${1^^}`, which bash 3.2 (the macOS default) does not have. The alias filter then accepted every name, and the backend check stopped every backend | `_provider_test_norm_into` upper-cases in-process from explicit letter lists and assigns with `printf -v` | The whole test run in the official `bash:3.2` image (3.2.57), repository mounted read-only, with the test's stub `docker` | 99 of 99 checks pass, as under bash 5 |
| Normalization forked a subshell for every environment entry | The same in-process function | Code reading: the only command substitution left in the check is the one `docker inspect` | No fork per entry |
| The reserved-name filter refused harmless extras such as `DOKENE_AI_OPENAI_TIMEOUT` | Limited to the key, base URL, trusted hosts and provider properties, plus the configuration-loading variables | New positive cases; negative control with the old filter | Timeout, output token and WhatsApp base URL extras are written; the control fails 3 checks |
| The mount check missed a mount over the config tree root | Fails closed on `/run`, `/run/secrets`, `/run/secrets/dokene`, any file there but the signing key, and an application or reserved file under `/run/secrets` | 7 new mount cases; negative control with the old check | All fail closed and the signing-key mount passes; the control fails 7 checks |
| The stub's accepted key could be overridden with `STUB_EXPECTED_API_KEY` | The key is fixed in the source | Stub run on loopback with that variable set to another value; a test that the name is gone | Synthetic key 200, the other value 401; the control with the override restored fails 2 checks |
| The guard against hand-set stub URLs missed quoted, `[::1]`, `https` and spaced forms | Broader expression, with files picked by `find` so it also works with BusyBox grep | Six sample forms, each must be caught; negative control with the old expression | All six caught, none in the repository; the control fails 5 checks |
| Counters required each canonical name "exactly once", which Docker cannot break | Presence flags; the impossible duplicate case was removed | Test run | Pass |
| The test reimplemented the literal secret search | Uses `contains_literal` from `scripts/lib/secret-match.sh` | Test run | Pass |

ShellCheck (`-x`) on both files and `scripts/tests/dev-env-smoke.sh` pass on that tree.

## F4 fix (fourth commit)

Round 3 of the independent verification reported optional finding F4, introduced by the narrower filter. An extra under an unreserved name could hold a Compose substitution such as `"${DOKENE_AI_OPENAI_API_KEY}"`. Compose fills that in from the host environment or `.env`, so the configured key would go to the stub in the request body and land in its local `last-*.json`. The backend check does not see substitutions. The fourth commit refuses any `$` in an extra value, which also closes the `${…}` case of finding F3. The base URL already allowed no `$`.

| Check | Kind | Outcome |
| --- | --- | --- |
| `scripts/tests/provider-test-double-test.sh` with three new refusals (`${…}`, `$NAME`, `$$`) | automated | 102 checks pass under bash 5 and in the `bash:3.2` image (network disabled, repository mounted read-only) |
| Negative control: the third commit's value expression | automated | 4 checks fail: the three `$` cases and the untouched-override check |
| ShellCheck (`-x`) on both files; `scripts/tests/dev-env-smoke.sh` | automated | Pass |

## Limitations

- The changed evidence scripts record past runs and start with paths of the machine they ran on. They were changed so that a rerun cannot send a real key to a test double, and checked through the library's tests, the stub run and static checks; they were not rerun end to end against the local stack. The outputs in `issue-123-evidence/outputs.txt` are from the original run.
- Whether the key reached the stub in the original runs was not established, as the Issue notes: the stub never logged headers, and the refused and blackholed destinations had no listener. This record proves that a rerun cannot send it, not what was sent before.
- The backend still sends whatever key is configured to an allowed base URL, loopback included. A local gateway on loopback that forwards to a provider is a legitimate deployment, so the backend cannot refuse a real key there; the guard is in the test harness, where the destination is chosen.
- Spring's handling of each alias was not exercised against a running backend. The checks follow the relaxed binding rules (case and separators ignored) and fail closed beyond them; a property source the library does not know, such as a config file baked into a custom image, is outside what a harness check can see.
- `res11.mjs` and the live cases of `res01.sh` use the real provider on purpose and are out of scope; `res01.sh` already sets its key per case.
