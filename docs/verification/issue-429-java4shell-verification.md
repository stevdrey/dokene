# Issue #429 Java4Shell (CVE-2026-114938): verification record

Status: single PR, part of epic [#305](https://github.com/stevdrey/dokene/issues/305), toward [#429](https://github.com/stevdrey/dokene/issues/429). Assessment: [java4shell-cve-2026-114938.md](../security/java4shell-cve-2026-114938.md). Decision: [ADR 0034](../adr/0034-java-27-runtime-baseline-and-java4shell-response.md). Rule: [security invariant 42](../security/security-invariants.md).

This record is sanitized. It holds no exploit input, no credential and no host detail beyond the presence or absence of one CPU flag. Every malformed input used is ordinary invalid Base64 or invalid cursor content.

## Environment

- **Run:** 2026-11-20, branch `429-securitycritical-assess-and-remediate-java4shell-cve-2026-114938-in-java-runtimes`, based on `main` at `42ee65e8`.
- **Host:** Linux 7.3.4 (Fedora 45), x86-64, Docker 29.10.0, GNU bash 5.3, ShellCheck. The host CPU reports `bmi2` and none of `avx512f`, `avx512bw`, `avx512vbmi`: it cannot reach the vulnerable intrinsic, so nothing here exercised it.
- **Local JDK for Gradle:** Azul Zulu 27 GA (`27+35`, unfixed), run with `JAVA_TOOL_OPTIONS=-XX:-UseBASE64Intrinsics`. CI uses Temurin 27 with `check-latest`, which the Adoptium API resolves to `jdk-27.0.1.1+4` (published 2026-11-18); the CI guard step proves that on the runner.
- **Not run here:** the full Compose stack. Another local stack held host port 8080, which the OIDC callback needs. The JVM-level evidence below uses the images built from this tree.

## Pins

Evidence rows in the format of [build-input-pinning.md](../security/build-input-pinning.md). All indexes list `linux/amd64` and `linux/arm64`.

| file:line | image | tag | index digest | platforms confirmed | resolved on | method |
| --- | --- | --- | --- | --- | --- | --- |
| `infra/docker/backend/Dockerfile:9` | `eclipse-temurin` | `27-jdk` | `sha256:5e6d9093c7a8e95adfaa8828253c58f2c6820116d07a062440a778358807578a` | amd64, arm64 | 2026-11-20 | `docker buildx imagetools inspect` |
| `infra/docker/backend/Dockerfile:27` | `eclipse-temurin` | `27-jre` | `sha256:acfb350b50e707addf3dcbb47f7052d1882d1e5f1670ce838a9c7838d82d4fe5` | amd64, arm64 | 2026-11-20 | `docker buildx imagetools inspect` |
| `infra/docker/keycloak/Dockerfile:3`, `:11` | `quay.io/keycloak/keycloak` | `26.8.2-3` | `sha256:7e66a96e4739239070aeaf72e4c5decf0f7f682cfc725b9f7ea2b336eaba38b3` | amd64, arm64 | 2026-11-20 | `docker buildx imagetools inspect` |

On the same date `eclipse-temurin:26-jdk` and `26-jre` still resolved to the digests pinned before the advisory (no Java 26 fix), and every platform of `eclipse-temurin:27-jdk`/`27-jre` reported `JAVA_VERSION=jdk-27.0.1.0` (image config, created 2026-11-11), so no official fixed Temurin 27 image existed yet.

## Runtime evidence

`scripts/check-java4shell-runtime.sh` was mounted read-only into containers of the images built from this tree with `docker build --no-cache`: `dokene-backend:issue429` (`sha256:b35338383d9a…`) and `dokene-keycloak:issue429` (`sha256:20c7fdf0f5b3…`).

| Runtime | JVM build | `UseBASE64Intrinsics` | Script status | Exit |
| --- | --- | --- | --- | --- |
| Backend image, image defaults | Temurin `27.0.1.0.2+1` | `false` (from `JAVA_TOOL_OPTIONS`) | `MITIGATED` | 0 |
| Backend image, `JAVA_TOOL_OPTIONS` emptied, no flag | Temurin `27.0.1.0.2+1` | `true` | `VULNERABLE` | 1 |
| Backend image, `--require-patched` | Temurin `27.0.1.0.2+1` | `false` | `MITIGATED`, refused | 1 |
| Backend application JVM started by `entrypoint.sh`, `JAVA_TOOL_OPTIONS` emptied | `27.0.1.0.2+1` | `false {command line}` (`jcmd 1 VM.flags -all` from a JDK container in the same PID namespace) | n/a | n/a |
| Keycloak image `26.8.2-3` (new) | Red Hat `21.0.13.1+2-LTS` | `true` | `PATCHED` | 0 |
| Keycloak image `26.8.2-2` (previous, `dokene-keycloak:local`) | Red Hat `21.0.13+9-LTS` | `true` | `VULNERABLE` | 1 |
| Local Temurin 26 (`26.0.2.1+1`) | | `true` | `VULNERABLE`, no fix available | 1 |
| Local Zulu 27 GA, with `-- -XX:-UseBASE64Intrinsics` | `27+35` | `false` | `MITIGATED` | 0 |

The backend process command line read from `/proc/1/cmdline` was `java -XX:-UseBASE64Intrinsics -jar /app/dokene.jar`. The image build log shows the build-stage Gradle JVMs picking up `-XX:-UseBASE64Intrinsics`. `docker compose config` resolves the `migrate` entrypoint to `java -XX:-UseBASE64Intrinsics -Dloader.main=…`. Every JVM accepted the flag without `-XX:+UnlockDiagnosticVMOptions`.

## Checks

| Id | Kind | Command | Result |
| --- | --- | --- | --- |
| C1 | automated | `./gradlew test` (JDK 27, toolchain 27), first run | 2850 tests, 1 failure: `DispatchRecoveryIntegrationTest.claimsThatMustBeLeftAloneCannotStarveTheOthers` (`BODY_REJECTED`) |
| C2 | automated | `./gradlew test --tests '*DispatchRecoveryIntegrationTest'`, twice | passed both times (see Findings) |
| C3 | automated | `./gradlew test --tests '*ClientCursorDecodingTest'` | 108 cases passed: six cursor types round-trip; null, blank, wrong alphabet, bad length and padding, embedded newline, percent-encoding, valid Base64 of bad content and 32-64 KiB inputs are all refused with `IllegalArgumentException("Invalid …")` |
| C4 | automated | `./gradlew test` (JDK 27), final run including C3 | see below |
| C5 | automated | `scripts/tests/check-java4shell-runtime-test.sh` | 25 checks passed (fixed and unfixed builds of 8, 11, 17, 21, 25, 26, 27, 28-ea; flag on the command line and in `JAVA_TOOL_OPTIONS`; `--require-patched`; usage and inspection errors) |
| C6 | automated | `shellcheck` on the script and its test | clean |
| C7 | automated | every script test run by `scripts.yml`, `workflow-pins.yml` and `frontend.yml` | all passed (`provider-test-double-test.sh` after its image baseline fixture took the new `JAVA_TOOL_OPTIONS`) |
| C8 | automated | `scripts/check-image-pins.sh`, `scripts/check-workflow-pins.sh` | passed |
| C9 | live | `docker build --no-cache` of the backend and Keycloak images, then the runtime evidence above | both built; results as tabled |
| C10 | not-run | CI on GitHub (`backend.yml` guard on the runner's Temurin) | runs on the pull request |

C4 result: `BUILD SUCCESSFUL`, 2958 tests (the 2850 of C1 plus the 108 of C3), 0 failures, 0 errors. `DispatchRecoveryIntegrationTest` passed in this run.

C11 (automated): `shellcheck scripts/verify-issue-311-anonymous-sessions.sh` clean after its `jcmd` gained `-J-XX:-UseBASE64Intrinsics`, and `jcmd -J-XX:-UseBASE64Intrinsics -l` exits 0 in the pinned `27-jdk` image. The script itself (a full-stack run) was not rerun.

## Findings

- **F1 (optional, pre-existing, unrelated to Java 27):** `DispatchRecoveryIntegrationTest` names its customer `"Recovery customer " + UUID.randomUUID()`. The rendered-text safety validator checks identity values, so some random names are rejected (`BODY_REJECTED`) and the test fails intermittently. It passed in isolation twice on the same JDK. Fix belongs in a separate change (a fixed, validator-safe name).
- **F2 (optional):** `UUID.fromString` accepts a short last group, so a cursor truncated by one character can decode to a different id rather than being refused. Pagination stays inside the tenant's own query, so this is not a security issue; recorded because C3 surfaced it.

## Limitations

- No production or other deployed environment is defined in this repository. CPU flags, image digests and JVM flags of such an environment are not evidenced here; the security page lists the commands for the deployer. Acceptance criterion 3 is therefore met for the local environment only.
- The backend image runs an unfixed Temurin build with the intrinsic disabled until official fixed `eclipse-temurin:27` images are published and the digests are bumped.
- No test executed the vulnerable intrinsic: the host lacks AVX-512 VBMI, and an exploit input would not belong in a public repository.
