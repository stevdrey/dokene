# Issue 432 verification: official Java4Shell-fixed Temurin 27 images

Issue [#432](https://github.com/stevdrey/dokene/issues/432), follow-up of [#429](https://github.com/stevdrey/dokene/issues/429), part of epic [#305](https://github.com/stevdrey/dokene/issues/305). Assessment: [java4shell-cve-2026-114938.md](../security/java4shell-cve-2026-114938.md). Decision: [ADR 0034](../adr/0034-java-27-runtime-baseline-and-java4shell-response.md), amendment for issue 432.

Status: **in progress.** On the resolution date, the official images held the fixed build on linux/amd64 only. The linux/arm64 images still held `jdk-27.0.1.0`, so the arm64 build fails by design until the digests are bumped again (see [Open items](#open-items)).

## Pin evidence

Resolved by following the manual pin steps in [build-input-pinning.md](../security/build-input-pinning.md). `JAVA_VERSION` per platform was read with `docker buildx imagetools inspect <ref> --format '{{json .Image}}'`.

| file:line | image | tag | index digest | platforms confirmed | resolved on | method |
| --- | --- | --- | --- | --- | --- | --- |
| `infra/docker/backend/Dockerfile:11` | `eclipse-temurin` | `27-jdk` | `sha256:f67511a7888677dbc4fb64ee6f17f9237c24d2b110f65a61716a3b516b6b3fa8` | linux/amd64 (`jdk-27.0.1.1+4`), linux/arm64 (`jdk-27.0.1.0`, unfixed) | 2026-11-24 | `docker buildx imagetools inspect` |
| `infra/docker/backend/Dockerfile:30` | `eclipse-temurin` | `27-jre` | `sha256:8f8cfda6393f63a029dbe83ceda4c19a31d268184662e96d9641f57afdaf870b` | linux/amd64 (`jdk-27.0.1.1+4`), linux/arm64 (`jdk-27.0.1.0`, unfixed) | 2026-11-24 | `docker buildx imagetools inspect` |

The Adoptium API listed the fixed `jdk-27.0.1.1+4` aarch64 JRE as published on 2026-11-23T15:02Z. The official image had not been rebuilt with it yet.

## Checks

| Check | Kind | Result |
| --- | --- | --- |
| Build with the previous (unfixed `27.0.1.0`) digests, linux/amd64, `--target build` and `--target runtime` | automated (local) | **Fails as intended**: `status: VULNERABLE`, the `--require-patched` step exits 1 in each stage |
| Build with the digests above, linux/amd64, `--no-cache` | automated (local) | **Passed**: both stages report `runtime-version: 27.0.1.1+4`, `status: PATCHED` |
| `scripts/check-java4shell-runtime.sh --require-patched` inside the built amd64 image | automated (local) | **Passed**: `Eclipse Adoptium (Temurin-27.0.1.1+4)`, `arch: amd64`, `status: PATCHED` |
| Image metadata of the amd64 image | automated (local) | `JAVA_VERSION=jdk-27.0.1.1+4`, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` (no mitigation flag) |
| `image` job of `backend.yml`, linux/amd64 and linux/arm64 (native runners) | automated (CI) | See the pull request checks. arm64 is expected to fail until the official arm64 image is fixed |
| `scripts/tests/provider-test-double-test.sh`, `check-java4shell-runtime-test.sh`, `dev-env-smoke.sh`, `check-image-pins-test.sh` | automated (local) | Passed |
| `scripts/check-image-pins.sh`, `scripts/check-workflow-pins.sh`, `docker compose config -q` | automated (local) | Passed |
| linux/arm64 build on the development host | not-run | The host has no arm64 emulation. arm64 is verified only by the CI job |
| Full stack start (`migrate`, `backend`) on the new image | not-run | Covered by later manual QA; the application JAR and entrypoint did not change apart from the removed flag |

## Open items

- Bump both digests again once `eclipse-temurin:27-jdk` and `27-jre` report `27.0.1.1` or later for linux/arm64, and add the evidence rows here.
- Confirm that the `image` job passes on both platforms.
- CodeQL CLI 2.27.5 still analyzes Java up to 26 only, so `-PcodeqlJavaRelease=26` stays (`.github/workflows/codeql.yml`, `backend/build.gradle`).
