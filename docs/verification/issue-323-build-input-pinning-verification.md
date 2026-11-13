# Issue #323 build input pinning: verification record

Status: single PR. It closes the image and Gradle Wrapper parts of audit finding L13 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md): every container image was chosen by a movable tag, and the wrapper ran its distribution without checking it. The rule is in [build-input-pinning.md](../security/build-input-pinning.md) and security invariant 34. Gradle dependency verification and the Testcontainers `ryuk` helper image are deferred there in writing.

This record is sanitized: it names digests, checksums, tags, file lines, commands and run identifiers only. It contains no token, key or secret.

## Environment

- **Run:** 2026-11-13 (UTC), branch `fix/323-verify-build-inputs-pin-images`, based on `main` at `341464a`. Both prerequisites were on that base: `scripts/check-workflow-pins.sh` with `workflow-pins.yml` (issue #309) and `scripts/verify-issue-311-anonymous-sessions.sh` (issue #311).
- **Recheck before the change:** `scripts/check-image-pins.sh`, written first, listed 15 references on the unchanged tree: the eight image lines in the table below, the two `# syntax=docker/dockerfile:1` lines, four `new PostgreSQLContainer("postgres:17-alpine")` literals in three test classes, and the `JDK_IMAGE` default in the issue 311 script. The wrapper pointed at `gradle-9.9.0-bin.zip` with no `distributionSha256Sum`.
- **Tested revision:** the commit named in [CI runs on the pull request](#ci-runs-on-the-pull-request). The local results below were run on the working tree that became that commit. A record cannot name its own commit hash, so the CI section is added in a later commit that changes only this record.
- **Toolchain:** Docker 29.7.2 with buildx 0.36.1 on arm64 macOS; Gradle Wrapper 9.9.0 with the Java 26 toolchain declared by the build; `/bin/bash` 3.2.57 for the check and its test; ruby's `YAML.load_file` to parse the two edited YAML files.

## Evidence table

No image changed version. Each digest is the index digest the registry reported for the tag already in use, and each index lists both platforms. The media type was an OCI image index for seven lines and a Docker manifest list for `alpine/socat`.

| file:line | image | tag | index digest | platforms confirmed | resolved on | method |
| --- | --- | --- | --- | --- | --- | --- |
| `compose.yaml:3` | `postgres` | `17-alpine` | `sha256:d3244e8ce3f165ee3c93d9c4a4b1d0fe48b3a89e2287b717fdcbbde92d405065` | linux/amd64, linux/arm64/v8 | 2026-11-13 | `docker buildx imagetools inspect` |
| `compose.yaml:130` | `alpine/socat` | `1.8.0.3` | `sha256:beb4a68d9e4fe6b0f21ea774a0fde6c31f580dde6368939ed70100c5385b015e` | linux/amd64, linux/arm64 | 2026-11-13 | `docker buildx imagetools inspect` |
| `infra/docker/backend/Dockerfile:4` | `eclipse-temurin` | `26-jdk` | `sha256:a45ef8c9f6304700665c5a5ac7250122e951831593bfa95859c295aea67f7d16` | linux/amd64, linux/arm64/v8 | 2026-11-13 | `docker buildx imagetools inspect` |
| `infra/docker/backend/Dockerfile:20` | `eclipse-temurin` | `26-jre` | `sha256:0dbf52b3861a84e2a19c503245504e5a71a5f071ccb9c48997efd4a3454a52b7` | linux/amd64, linux/arm64/v8 | 2026-11-13 | `docker buildx imagetools inspect` |
| `infra/docker/frontend/Dockerfile:4` | `node` | `24-alpine` | `sha256:116b800c3754b440ee0aa3ed2f20f5bf3d99a554bfd9adeea09ea8017f6e85f5` | linux/amd64, linux/arm64/v8 | 2026-11-13 | `docker buildx imagetools inspect` |
| `infra/docker/frontend/Dockerfile:14` | `nginxinc/nginx-unprivileged` | `1.29-alpine` | `sha256:0c79d56aee561a1d81c63f00eee5fb5fe29279560cdc55e91425133104c7fbe6` | linux/amd64, linux/arm64 | 2026-11-13 | `docker buildx imagetools inspect` |
| `infra/docker/keycloak/Dockerfile:1` | `quay.io/keycloak/keycloak` | `26.7.3` | `sha256:29be7252db0a106f1cd2ac17b9a56ff2668073da645638a38b9fc67deeb2d6c4` | linux/amd64, linux/arm64 | 2026-11-13 | `docker buildx imagetools inspect` |
| `infra/docker/keycloak/Dockerfile:9` | `quay.io/keycloak/keycloak` | `26.7.3` | `sha256:29be7252db0a106f1cd2ac17b9a56ff2668073da645638a38b9fc67deeb2d6c4` | linux/amd64, linux/arm64 | 2026-11-13 | `docker buildx imagetools inspect` |

The two Keycloak lines carry one digest.

## Wrapper checksum

- `https://services.gradle.org/distributions/gradle-9.9.0-bin.zip.sha256` and the "Binary-only (-bin) ZIP Checksum" for 9.9.0 on `https://gradle.org/release-checksums/` both gave `0b00b0ffe07c4d4789c12d4935a44a7057d55774b7540bd4a3f76f336deb177a`. That value is in `gradle-wrapper.properties`.
- With an empty `GRADLE_USER_HOME`, `./gradlew --version` downloaded the distribution and printed `Welcome to Gradle 9.9.0!`.
- In a scratch copy of the wrapper with the first character of the value changed from `0` to `1`, and a second empty `GRADLE_USER_HOME`, the same command stopped with `Verification of Gradle distribution failed!`, naming the expected and the actual checksum.

## Local verification

| Check | Result |
| --- | --- |
| `./scripts/tests/check-image-pins-test.sh` | 30 checks pass: the real tree and the default path exit 0; 14 fail cases print exactly the expected `<case>/<file>:<line>: <reference>` lines; 7 pass cases exit 0; a single file path works; an empty directory, a directory with no checked file and a missing path exit 2 with a message |
| `./scripts/check-image-pins.sh` | exit 0, no output |
| `./scripts/tests/check-workflow-pins-test.sh` | all checks passed (the checkout in the new job reuses the SHA the file already used) |
| `ComposeImagesTest` | 7 pass: a good file, quotes and a trailing comment, a missing file, zero matches, a tag only line, two matches, and the real `compose.yaml` |
| `./gradlew check` | 2717 tests, 0 failures, 0 errors, 0 skipped. Every Testcontainers class started PostgreSQL as `postgres@sha256:d3244e8c…` on arm64 |
| Test task input | `./gradlew test --tests '*ComposeImagesTest'` twice: the second run was `UP-TO-DATE`. After one comment line was appended to `compose.yaml` the task ran again. The line was then removed |
| `docker compose build --no-cache keycloak backend frontend` | all three images built, with no `# syntax=` line in the backend and frontend Dockerfiles. The backend build ran `./gradlew --version` inside the image, so the wrapper checksum was verified there too |
| `./scripts/dev-env.sh up --no-build`, then `GET /` and `GET /api/session` through nginx | all five services healthy on the pinned images; `200` and `401` |
| Issue 311 script, default image | resolved `eclipse-temurin:26-jdk@sha256:a45ef8c9…` from the `AS build` line; `DOKENE_VERIFY_JDK_IMAGE` still overrides it; a tag only line and a missing line both exit 1 with a message |
| `.github/dependabot.yml` | parses; five entries; the three existing entries are byte for byte those on `main` at `341464a` |

`./scripts/tests/dev-env-smoke.sh` has one failing check on this machine, "secret files: Compose-resolved values, safe DOKENE_SECRETS_DIR, recreation survives a failed removal (#308)". It fails the same way on an untouched checkout of `main` at `341464a`, so it is not caused by this change; every other check passes. The `scripts` workflow run below is the result that counts for that script.

Not proven locally: the amd64 side of the backend and Keycloak images, which no workflow builds. For those it rests on the platform list in the table. CI proves PostgreSQL and the two frontend images on amd64.

## After merge

Dependabot's configuration page and the first run of the `docker` and `docker-compose` entries can only be observed after the merge. Their result (no configuration error, and whether the first run proposed a digest bump, a tag bump or nothing, as one group, without a major version) is posted as a comment on issue #323, not in this record.

## CI runs on the pull request

Added after the pull request's checks have run.
