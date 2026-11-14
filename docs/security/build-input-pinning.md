# Build input pinning

Every container image this repository builds on or runs used to be chosen by a tag such as `postgres:17-alpine`. A tag is a movable label: the registry, or whoever controls the publisher's account, decides what a build really gets. The Gradle Wrapper likewise downloaded its distribution without checking it. This page states the rule that closes the image and wrapper parts of audit finding L13 of the [security audit of 2026-11-02](security-audit-2026-11-02.md) (issue [#323](https://github.com/stevdrey/dokene/issues/323), spec 0017), how to pin an image by hand, how to set the wrapper checksum, how Dependabot keeps the pins current, what a reviewer checks on a bump, and what is deferred. Security invariant 34 in [security-invariants.md](security-invariants.md) points here. The sibling rule for GitHub Actions is in [github-actions-pinning.md](github-actions-pinning.md).

A digest pin fixes content, not trust. It proves an image is the one resolved on the recorded date, not that the publisher's image was safe. Image scanning stays with the Trivy workflow.

## The rule

Every reference to an image that is not built from this tree has the form

```
<name>:<tag>@sha256:<64 lowercase hex>
```

```dockerfile
FROM eclipse-temurin:26-jdk@sha256:<index digest> AS build
```

```yaml
services:
  postgres:
    image: postgres:17-alpine@sha256:<index digest>
  backend:
    image: dokene-backend:local
```

```java
static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(ComposeImages.postgres());
```

- The digest is the multi platform index digest (the digest of the manifest list that covers every platform), so one pin works on arm64 developer machines and amd64 runners.
- The tag stays beside the digest. It is what a reviewer reads and what Dependabot follows.
- One `name:tag` carries one digest in every file. The two Keycloak `FROM` lines cannot drift apart.
- Each external image has exactly one file that holds its pin: PostgreSQL and socat in `compose.yaml`, the others in their Dockerfile. Java tests take PostgreSQL from `compose.yaml` through `ComposeImages.postgres()`, and `scripts/verify-issue-311-anonymous-sessions.sh` takes its JDK image from the `AS build` line of `infra/docker/backend/Dockerfile`. Dependabot cannot rewrite Java or shell, so a pin written there would have no update path.
- Forbidden: a tag alone, no tag (which means `latest`), a digest with no tag, a `FROM` that names a variable, `COPY --from=<external image>` without a full pin, a `# syntax=` line without a digest (the standard form is no `# syntax=` line), an image reference as a string literal in Java, a shell `*_IMAGE=` literal that is not a full pin, and two digests for one `name:tag`.

Exceptions: an image built from this tree, which has the exact shape `dokene-<name>` or `dokene-<name>:<tag>` with no `/` (so a registry host that starts with `dokene-` is not exempt); `FROM scratch`; a reference to an earlier build stage or a named build context; and the `testcontainers/ryuk` helper image, which no file here names (see [Deferred](#deferred)). There are no other exceptions.

`backend/gradle/wrapper/gradle-wrapper.properties` always carries `distributionSha256Sum` for the distribution named in `distributionUrl`.

## Enforcement

`scripts/check-image-pins.sh [path...]` applies the rule. With no argument the path is `.`. Each path is a file or a directory searched recursively, skipping any directory named `.git`, `node_modules`, `build`, `dist`, `.gradle`, `.claude` or `.worktrees`. It prints one `<file>:<line>: <reference>` line per violation, in the order read, with second digests for an already pinned `name:tag` last. Exit 0 means every reference is pinned, 1 means at least one violation, 2 means a path does not exist or holds no file the check reads. It needs bash, `find`, `sort`, `tr` and `basename`, no network and no Docker, and runs the same on the bash 3.2 that macOS ships and on the runner.

What it reads:

1. **Dockerfiles** (`Dockerfile`, `Dockerfile.*`, `*.Dockerfile`): every `FROM` line, in any letter case, after skipping `--` flags. The reference passes when it is `scratch`, a stage named earlier in the same file, or a full pin.
2. In the same files, every `--from=<value>` on a `COPY` or `RUN` line. A value with no `/`, `:` or `@` is a stage or a named build context and passes; any other value must be a full pin.
3. In the same files, a `# syntax=` line fails unless its value is a full pin.
4. **Compose files** (`compose*.yml`, `compose*.yaml`, `docker-compose*.yml`, `docker-compose*.yaml`): every `image:` line, with a trailing comment and one pair of quotes removed. It passes when it is a local image or a full pin.
5. **Java files**: a line with `new <Something>Container("...` or `DockerImageName.parse("...` fails, pinned or not. `ComposeImages.java` and `ComposeImagesTest.java` are exempt by file name.
6. **Shell files** (`*.sh`): an assignment to a name ending in `IMAGE`. It passes when the value is empty, computed (starts with `$`), a local image or a full pin; a `${NAME:-default}` value is judged by its default.
7. Comment lines are skipped, except for rule 3.
8. After all files are read, every `name:tag` must map to one digest.

`.github/workflows/workflow-pins.yml` runs the `image-pins` job on every pull request and every push to `main`: `scripts/tests/check-image-pins-test.sh`, then the check on the tree, then one step asserting that the wrapper properties file has a `distributionSha256Sum` line of 64 lowercase hex characters. The test writes its cases into a temporary directory at run time. No case file is committed, because a committed file named `Dockerfile` would be read by the Trivy misconfiguration scan and by the check's own scan of the tree. `.github/workflows/backend.yml` also runs when `compose.yaml` changes, so a PostgreSQL pin change runs the backend tests.

Limits of the check, by design: it is line based. It does not see a `FROM` continued over two lines, a compose flow mapping (`{image: x}`), an image passed straight to `docker run` in a script with no `*_IMAGE` variable, a `RUN --mount=type=bind,from=<image>`, a `COPY --from=alpine` (a bare image name reads as a stage name), or a Java reference built from concatenated strings or held in a constant. The rule forbids those forms and review covers them.

What the check does not prove: that the digest is the one the named tag pointed at. It enforces the form only, so any 64 hex digest beside a plausible tag is green. The proof is the reviewer step below. The check also judges a pull request with the pull request's own copy of the script, the test and the workflow, so a change to any of the three needs the same attention in review as a changed pin.

Until the owner marks the check required, a red `image-pins` run is visible to every reviewer but does not block a merge.

## Manual pin steps

Use these for every new image, and whenever you bump one by hand.

1. Run `docker buildx imagetools inspect <name>:<tag>`. It contacts the registry and prints the reference, the media type, the digest, and one entry per platform.
2. Confirm the media type is an image index or a manifest list, and that the platform list has `linux/amd64` and `linux/arm64`. If the image is single platform, stop and raise it in the pull request, since one pin cannot serve both architectures.
3. Write `<name>:<tag>@sha256:<that digest>`. If another file already pins the same `name:tag`, it must carry the same digest; if the registry now reports a different one, bump every file together.
4. Add the evidence row to the verification record of the change (table format below), then run `./scripts/check-image-pins.sh`.

Resolve the tag you already run, do not upgrade while pinning. Upgrading is a later pull request, so a pinning change stays a pure security change.

## Evidence table format

One row per pinned line, in the verification record of the change that added or moved it:

| file:line | image | tag | index digest | platforms confirmed | resolved on | method |
| --- | --- | --- | --- | --- | --- | --- |

`resolved on` is the UTC date, `method` is `docker buildx imagetools inspect`. The first table is in [the issue #323 verification record](../verification/issue-323-build-input-pinning-verification.md).

## Wrapper checksum steps

1. Read the version from `distributionUrl` in `backend/gradle/wrapper/gradle-wrapper.properties`.
2. Fetch the checksum file Gradle publishes beside the distribution (`<distributionUrl>.sha256` under `https://services.gradle.org/distributions/`, with the properties file's `\:` escape written as a plain `:`), and read the value for the same file on <https://gradle.org/release-checksums/>. The two must be equal. If they differ or one is missing, stop.
3. Set `distributionSha256Sum=<value>` in the properties file.
4. Prove it: `GRADLE_USER_HOME=<empty scratch directory> ./gradlew --version` succeeds. In a scratch copy of the wrapper with one character of the value changed, and a second empty scratch directory as `GRADLE_USER_HOME`, the same command fails with a checksum mismatch. The wrapper checks only when it downloads, so a cached distribution proves nothing.
5. For a Gradle upgrade by hand, run the `wrapper` task with `--gradle-version` and `--gradle-distribution-sha256-sum`, taking the value from step 2.

When Dependabot bumps `distributionUrl`, check whether it also changed `distributionSha256Sum`. If it did not, the wrapper fails with a mismatch on a clean runner, so the bump cannot merge unverified: push the new value from step 2 to that pull request.

## How Dependabot keeps the pins current

`.github/dependabot.yml` has a `docker` entry for the three Dockerfile directories and a `docker-compose` entry for `/`. Each opens at most one grouped pull request per week, only after a release has been public for seven days, and never proposes a major version: a move to a new Java, PostgreSQL (which breaks an existing data volume) or Keycloak major is a hand change with its own issue, because no workflow builds the backend or Keycloak image and such a bump could arrive on green CI.

How Dependabot treats a line with both a tag and a digest, and whether the group and the major version rule hold for these two ecosystems, was not confirmed before the change. What its first run did is recorded as a comment on issue #323. Where Dependabot does not update a line the rule covers, the manual pin steps are the update path for that line.

A pinned image no longer picks up upstream security patches by itself. Patches arrive only through a bump, so the pins depend on this weekly flow staying healthy.

## What a reviewer checks on a bump

1. For each changed line, run manual pin steps 1 and 2 against the tag on the line and confirm the digest matches and both platforms are listed.
2. Build each changed image with no cache (`docker compose build --no-cache <service>`), since CI builds only the frontend image.
3. For a PostgreSQL bump, confirm `backend.yml` ran. For a PostgreSQL or Keycloak bump, start the local stack once on its existing volumes.
4. Read the tag: a major version move never comes from Dependabot.
5. Merge on green CI.

## Deferred

- **Gradle dependency verification** (`gradle/verification-metadata.xml`). Artifacts from Maven Central and the Gradle plugin portal are still trusted on TLS and repository integrity alone. Dependabot does not update the metadata file (dependabot-core issue #1996), so with verification on, each weekly Gradle bump would fail until a person regenerates and reviews a large XML diff, and the likely outcome is regeneration without review. Reopen when Dependabot or another update tool can maintain the file.
- **The Testcontainers `ryuk` helper image.** Testcontainers pulls `testcontainers/ryuk` by a version tag the library chooses, on every backend test run, privileged and with the Docker socket mounted. A pin in `testcontainers.properties` has no update path and can fall out of step with the library. Reopen when there is a way to keep it in step with the Testcontainers version, or accept it in writing.
- **The scanner binaries the Trivy and gitleaks actions download at run time** stay a separate item, carried over from the GitHub Actions pinning work.
