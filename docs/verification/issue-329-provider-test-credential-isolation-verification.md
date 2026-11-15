# Issue #329 Credentials in provider failure tests: verification record

Status: single PR, part of epic [#305](https://github.com/stevdrey/dokene/issues/305). It closes [#329](https://github.com/stevdrey/dokene/issues/329): the provider failure scripts of the resilience suite pointed the backend at a fault-injection stub, a refused port or a blackholed address through a Compose override that set only the provider and the base URL, so the backend kept the OpenAI key configured in `.env` and sent it to each of those destinations. The rule now holds as [security invariant 37](../security/security-invariants.md).

This record is sanitized: it gives outcomes, counts and settings only. It contains no credential, key, request body or exploit detail. Every key used below was synthetic: the fixed test key of the library, or a real-looking value generated for the run and discarded with its temporary directory. No real provider key was read, and no OpenAI call was made.

## Environment

- **Run:** 2026-11-14, branch `329-security-isolate-credentials-in-provider-failure-tests`, based on `main` at `dc0d27a`. The analysed revision of the Issue was `a328848`; every analysed location was rechecked on `dc0d27a`, with the result below.
- **Tested revisions:** each section's checks ran on the working tree that then became one commit of this branch on top of `dc0d27a`. A commit cannot name its own hash, so the table gives the commit together with its Git tree id; `git rev-parse <commit>^{tree}` reproduces it. The eighth commit is identified by the blob ids of the files it changes, apart from this record.

  | Commit | Tree | Checks |
  | --- | --- | --- |
  | `e2f7f60` | `7fda08fae337a2a7bf9324b5f119618691015be8` | Checks |
  | `4df6167` | `8e001482096f47a285b374e43f393ee3fdbce7ee` | Spring relaxed binding |
  | `5df920b` | `b11e16772c8bb22cf3721e00f0ef52f7cb1fbc10` | Code review fixes |
  | `3177a27` | `6dad74005107c28aba5e9f458d62d3ca4e37641a` | F4 fix |
  | `6ee51bb` | `1e897348ac3c700508f79b3de953d0574eb5a326` | F2, F3 and F5 fixes |
  | `6a1b7e3` | `3988608eeea8c7eafd979ed21c9f7ca8c92e9ff2` | F6 and F7 fixes |
  | `dde3759` | `7b26d4324ac89bb782484569c6b655b522815085` | F8 and F9 fixes |
  | eighth commit | `scripts/lib/provider-test-double.sh` `1a38bd6a24a1db2346d54596a80b930779f6e4fc`, `scripts/tests/provider-test-double-test.sh` `63e13666c4b023b733a00ecf120170b714f09433`, `docs/security/security-invariants.md` `23da243bf5b321a89897f3d9ded096a08ce0ee43`, `.github/workflows/scripts.yml` `19f719d50c525b2569d61c1c76756a25930bd17f`, `docs/verification/issue-121-evidence/scripts/sec121-d.sh` `0b02caeefb6d963a543f1c57e3909ed218e75870`, `docs/verification/issue-120-evidence/scripts/ai120-c.sh` `9287f08963ac45c1bc689cc9d769d7a89580a255`, `docs/verification/issue-123-evidence/scripts/res10.sh` `c0611028c97702018e582136a3cb0fac0d21fe21`, `docs/verification/issue-123-evidence/scripts/README.md` `7bbbe2ad11c1d029f7870326ba0ce33f3920cbaa` | Code review fixes, allowlist check |

  An independent verifier checked each of these candidates (rounds 1 to 8).
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

Checks on the working tree that became the second commit (`4df6167`, see Tested revisions):

| Check | Kind | Outcome |
| --- | --- | --- |
| `scripts/tests/provider-test-double-test.sh` | automated | 82 checks pass. The new ones cover 13 refused alias and configuration names, 5 malformed extra lines, a real-looking key under three key spellings, a base URL alias on the real endpoint, `SPRING_APPLICATION_JSON`, `SPRING_CONFIG_*`, `JDK_JAVA_OPTIONS` with a `-D` key, a mounted key file and a duplicated canonical key. All of them fail closed, and the backend's own secret mount and an alias that holds the synthetic key pass |
| Negative control: the new tests run against the first commit's library | automated | 30 checks fail, one for each alias case |
| The `docker inspect` template run against a local container, only the line prefixes counted | live, local | environment entries and mounts are listed as `ENV` and `MOUNT` lines; the trailing blank line is ignored |
| ShellCheck 0.11.0 (`-x`) on both files; `scripts/tests/dev-env-smoke.sh` | automated | Pass |

## Code review fixes (third commit)

A code review of the pull request found eight non-blocking issues. They were fixed in the third commit and checked on the working tree that became that commit (`5df920b`, see Tested revisions):

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

## F2, F3 and F5 fixes (fifth commit)

The fourth verification round left three optional findings open. They were fixed in the fifth commit (`6ee51bb`, see Tested revisions) and checked on its working tree:

| Finding | Fix | Check | Outcome |
| --- | --- | --- | --- |
| F2: the record named the tested revisions without ids | The Tested revisions table above: the commit and tree id of each verified candidate, and the blob ids of the fifth commit's files | Manual comparison with `git log --format='%h %T'` | Each commit's tree id matches |
| F3: a config file mounted where Spring Boot loads one, and JVM argument files, passed the backend check | The check fails closed on mounts at `/app`, `/app/config` and below, `/config` and below, and on any mounted `application*.yml`, `.yaml` or `.properties` file. It also fails closed on JVM options that read an argument file (`@file`, `-XX:VMOptionsFile`, `-XX:Flags`). The image works from `/app` | 9 new mount cases and 5 JVM option cases; negative control with the fourth commit's library | All fail closed. The signing-key mount, mounts away from those locations and ordinary JVM options pass. The control fails 14 checks |
| F5: the guard against hand-set base URLs passed when it could not scan, and missed URLs set through a variable, a printf template or in upper case | The guard returns 0 only after scanning at least one script with no `find` or `grep` error. It catches any non-empty value given to the base URL, in any case; an empty reset and reads are allowed | 10 sample forms that must be caught; a sample of resets and reads that must not be; a missing and an empty directory; the repository tree; negative control with the old guard | All as expected. The repository's only match is the allowed synthetic-key line of issues 246-253. The control fails 6 checks |

`scripts/tests/provider-test-double-test.sh` passes 125 checks under bash 5 and in the `bash:3.2` image (network disabled, repository mounted read-only). ShellCheck (`-x`) on both files and `scripts/tests/dev-env-smoke.sh` pass.

## F6 and F7 fixes (sixth commit)

The fifth verification round reported two new optional findings. They were fixed in the sixth commit (`6a1b7e3`, see Tested revisions) and checked on its working tree:

| Finding | Fix | Check | Outcome |
| --- | --- | --- | --- |
| F6: the hand-set base URL guard missed bracket assignments such as `process.env['…'] = …`. It dropped any hit line that merely contained the allowed key text, and did not scan `.py` scripts | The expression accepts a closing quote or bracket after the name and refuses comparisons (`===`). The allowed hit must be the exact harness line in its own file. The scan covers `.sh`, `.bash`, `.mjs`, `.js`, `.py`, `.java`, `.yaml`, `.yml`, `.conf` and `.patch` files | Four new sample forms (bracket assignment in JS and Python, an object key, the allowed text in a trailing comment), samples spread over five file types, comparisons and reads that must pass, the exact allowed line, the same line in another file; negative control with the fifth commit's guard | All as expected. Across every scanned type, the repository's only match is the allowed line. The control fails 12 checks |
| F7: a mount that replaces the application, such as `/app/dokene.jar` or `/app/entrypoint.sh`, passed the backend check | The check fails closed on any mount under `/app` and on mounts over the JRE (`/opt/java`) and `/usr`, `/bin`, `/sbin`, `/lib` and `/lib64` | 9 new mount cases; negative control with the fifth commit's library | All fail closed. The signing-key mount and mounts such as `/var/lib/dokene`, `/app-data` and `/opt/config.txt` pass. The control fails 9 checks |

`scripts/tests/provider-test-double-test.sh` passes 140 checks under bash 5 and in the `bash:3.2` image (network disabled, repository mounted read-only). ShellCheck (`-x`) on both files and `scripts/tests/dev-env-smoke.sh` pass.

## F8 and F9 fixes (seventh commit)

The sixth verification round reported two new optional findings. They were fixed in the seventh commit (`dde3759`, see Tested revisions) and checked on its working tree:

| Finding | Fix | Check | Outcome |
| --- | --- | --- | --- |
| F8: the hand-set base URL guard missed setter calls (Java `environment().put`, Python `os.environ.setdefault` and `os.putenv`, bash `printf -v`, JS `??=`), other spellings (`DOKENE_AI_OPENAI_BASEURL`, `-Ddokene.ai.openai.base-url`) and `.ts` and `.json` files | The guard no longer recognizes assignment forms. It flags every mention of the setting in any spelling Spring binds, in `.sh`, `.bash`, `.mjs`, `.js`, `.ts`, `.py`, `.java`, `.json`, `.yaml`, `.yml`, `.conf`, `.properties` and `.patch` files. The only exceptions are three reviewed lines (two reads and the issue 246-253 harness), each matched by file and exact content | Ten new sample forms spread over eight file types; a script without a mention; a reviewed read in its own file and the same read elsewhere; negative control with the sixth commit's guard | All as expected. The repository's three mentions are the reviewed lines. The control fails 15 checks |
| F9: code could be injected by routes the mount rule did not list (`-javaagent` with a mounted jar, `LD_PRELOAD`, `/etc/ld.so.preload`) | The check fails closed on a set `LD_*`, `LOADER_*` or `CLASSPATH` variable. It also fails closed on JVM options with `-javaagent`, `-agentpath`, `-agentlib`, `-Xbootclasspath`, `--patch-module` or `-Dloader.*`, and on mounts at `/etc/ld.so*` | 11 code-loading cases and 2 linker mounts; the image's locale and JRE variables and an empty `LD_PRELOAD`; negative control with the sixth commit's library; the running backend's variable names and JVM options compared with the new rules, printing names and match counts only | All fail closed and the image's variables pass. The running backend has no variable or option that matches. The control fails 13 checks |

`scripts/tests/provider-test-double-test.sh` passes 166 checks under bash 5 and in the `bash:3.2` image (network disabled, repository mounted read-only). ShellCheck (`-x`) on both files and `scripts/tests/dev-env-smoke.sh` pass.

## Code review fixes, allowlist check (eighth commit)

A second code review of the pull request found eight issues. Two of them were ways past the backend check: a multi-line environment value hid its continuation, and an entrypoint or command override was never inspected. The review also noted that the check was a denylist each round had to extend. The eighth commit replaces it with a comparison against the backend's baseline. A read-only comparison of the running backend showed the baseline fits exactly: its 53 environment names are the 46 of the compose file's backend service plus the image's 7. Its only mount is the compose secret target, and its entrypoint, command and working directory equal the image's.

| Finding | Fix | Check | Outcome |
| --- | --- | --- | --- |
| A multi-line environment value hid its continuation from every rule | A line of the inspection that is neither an entrypoint, command, working directory, environment entry nor mount fails closed, as does an entry without `=`. The container and the image are inspected separately, so a container value cannot add lines to the image's baseline | Multi-line model, `JAVA_TOOL_OPTIONS` and forged-entry cases; an entry without `=` | All fail closed |
| Entrypoint and command were never inspected | The entrypoint, command and working directory must equal the image's | A changed entrypoint with `-D` options, an added Spring argument, a changed working directory; read-only comparison on the running backend | All fail closed; the running backend matches its image |
| The CI path filter was narrower than the guard | The Script tests workflow runs on any change under `docs/verification/` | Workflow file reviewed | Filter matches the guard's scope |
| The check was a growing denylist | Environment names come only from the compose file's backend service, from `PROVIDER_TEST_EXTRA_NAMES` (which must pass the reserved-name rule) or from the image, whose variables must keep the image's values. Mounts come only from the compose file's secret targets. That closes the remaining finding F10 too (`-Xrun`, `-XX:OnError`, `BASH_ENV`, `PATH`, mounts at `/etc` and `/opt`) | 19 variables outside the baseline, 9 image variables with other values, 23 mounts outside the baseline, 8 reserved or malformed declared names, a missing or backend-less compose file, an unreadable image; the declared metrics names of `res10.sh` and the image's own values pass; the running backend compared with the same baseline, printing names and paths only | All as expected. The running backend passes every baseline rule and fails only the two value rules (it is not on a test double) |
| The reviewed lines were byte-exact copies | Reads are exempt in the two reviewed files when they carry the marker `# provider-test-double: reviewed read`. The harness line that sets the URL stays an exact match | An edited marked read passes, the marker in another file and a reviewed file's unmarked mention are caught | All as expected |
| Recorded `.json` and `.patch` files were scanned in full | `.json` is not scanned; in `.patch` files only added lines count | Context and removed lines pass, an added line is caught, a JSON capture passes | All as expected |
| The override filter and the check kept separate lists | One reserved-name rule, now including `LD_*`, `LOADER_*`, `CLASSPATH`, `BASH_ENV`, `ENV`, `PATH` and `JAVA_HOME`, is used for override extras and declared names | 8 new write-time refusals | All refused with status 2 |
| A `:` in a path broke the guard's parsing | Files are scanned one at a time (`find -print0`) | A reviewed read in a path holding `:` | Passes |

`scripts/tests/provider-test-double-test.sh` passes 195 checks under bash 5 and in the `bash:3.2` image (network disabled, repository mounted read-only). Negative controls fail as intended:
- The seventh commit's check, with the new compose helpers kept, fails 42 checks: every baseline, entrypoint, multi-line and write-time case.
- The seventh commit's guard fails 5 checks.

ShellCheck (`-x`) on both files and `scripts/tests/dev-env-smoke.sh` pass.

Finding F11 is partly addressed: nested YAML keys, doubled separators and the unscanned file types of the guard remain. The backend check now covers those spellings at run time for every script that uses the library, because any name outside the baseline fails closed.

## Limitations

- The changed evidence scripts record past runs and start with paths of the machine they ran on. They were changed so that a rerun cannot send a real key to a test double, and checked through the library's tests, the stub run and static checks; they were not rerun end to end against the local stack. The outputs in `issue-123-evidence/outputs.txt` are from the original run.
- Whether the key reached the stub in the original runs was not established, as the Issue notes: the stub never logged headers, and the refused and blackholed destinations had no listener. This record proves that a rerun cannot send it, not what was sent before.
- The backend still sends whatever key is configured to an allowed base URL, loopback included. A local gateway on loopback that forwards to a provider is a legitimate deployment, so the backend cannot refuse a real key there; the guard is in the test harness, where the destination is chosen.
- The hand-set URL guard is a text scan: a name built at run time (for example by joining strings) would not be seen. Scripts that use the library are still checked at run time by `provider_test_assert_backend`.
- The baseline trusts the compose file and the image built from this tree; a change to either is reviewed like any other code. A driver whose own override adds names must declare them in `PROVIDER_TEST_EXTRA_NAMES`, otherwise the check stops the backend.
- Spring's handling of each alias was not exercised against a running backend. The checks follow the relaxed binding rules (case and separators ignored) and fail closed beyond them; a property source the library does not know, such as a config file baked into a custom image, is outside what a harness check can see.
- `res11.mjs` and the live cases of `res01.sh` use the real provider on purpose and are out of scope; `res01.sh` already sets its key per case.
