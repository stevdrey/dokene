#!/usr/bin/env bash
# ABOUTME: Tests scripts/check-image-pins.sh against the real tree and one case directory per rule (issue #323, audit L13).
# ABOUTME: Cases are written into a temporary directory at run time; needs only bash, no network, no Docker.
#
# No case file is committed: a committed file named Dockerfile would be read by the Trivy misconfiguration scan and
# by the check's own scan of the tree. The shell cases are written with printf, never as a line of this file that
# starts with a name ending in IMAGE=, because this file is itself scanned.
#
# Usage: ./scripts/tests/check-image-pins-test.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHECK="$ROOT_DIR/scripts/check-image-pins.sh"
failures=0

WORK="$(mktemp -d)"
trap '[ -n "$WORK" ] && rm -rf -- "$WORK"' EXIT
mkdir "$WORK/cases" "$WORK/results"

# put <case>/<file>: writes stdin to that file under the cases directory.
put() {
    mkdir -p "$WORK/cases/$(dirname "$1")"
    cat >"$WORK/cases/$1"
}

# expect "description" <expected exit> <path argument or ""> [expected output lines...]
# Runs the check from the directory in $RUN_FROM with the path as a relative argument.
expect() {
    local description="$1" want_exit="$2" path="$3"; shift 3
    local got_exit=0
    if [ -n "$path" ]; then
        (cd "$RUN_FROM" && "$CHECK" "$path") >"$WORK/results/out" 2>"$WORK/results/err" || got_exit=$?
    else
        (cd "$RUN_FROM" && "$CHECK") >"$WORK/results/out" 2>"$WORK/results/err" || got_exit=$?
    fi
    printf '%s\n' "$@" >"$WORK/results/want"
    [ "$#" -eq 0 ] && : >"$WORK/results/want"
    if [ "$got_exit" -eq "$want_exit" ] && cmp -s "$WORK/results/out" "$WORK/results/want"; then
        printf '[pass] %s\n' "$description"
    else
        printf '[FAIL] %s (exit %s, wanted %s)\n' "$description" "$got_exit" "$want_exit" >&2
        sed 's/^/       stdout: /' "$WORK/results/out" >&2
        sed 's/^/       stderr: /' "$WORK/results/err" >&2
        failures=$((failures + 1))
    fi
}

# expect_message "description" <path argument>: exit 2 with nothing on stdout and a message on stderr.
expect_message() {
    expect "$1" 2 "$2"
    if [ -s "$WORK/results/err" ]; then
        printf '[pass] %s, with a message\n' "$1"
    else
        printf '[FAIL] %s, with a message\n' "$1" >&2
        failures=$((failures + 1))
    fi
}

D1=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
D2=89abcdef0123456789abcdef0123456789abcdef0123456789abcdef01234567

RUN_FROM="$ROOT_DIR"
expect "the real tree is fully pinned" 0 .
expect "the default path is the current directory" 0 ""

RUN_FROM="$WORK/cases"

# --- Fail cases -----------------------------------------------------------------------------------------------------

put from-tag-only/Dockerfile <<EOF
FROM alpine:3 AS build
EOF
expect "FROM with a tag only fails" 1 from-tag-only \
    "from-tag-only/Dockerfile:1: alpine:3"

put from-no-tag/Dockerfile <<EOF
FROM alpine
EOF
expect "FROM with no tag fails" 1 from-no-tag \
    "from-no-tag/Dockerfile:1: alpine"

put from-digest-no-tag/Dockerfile <<EOF
FROM alpine@sha256:$D1
EOF
expect "FROM with a digest and no tag fails" 1 from-digest-no-tag \
    "from-digest-no-tag/Dockerfile:1: alpine@sha256:$D1"

put from-bad-digest/Dockerfile <<EOF
FROM alpine:3@sha256:0123456789abcdef
FROM alpine:4@sha256:0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF
EOF
expect "FROM with a short or an uppercase digest fails" 1 from-bad-digest \
    "from-bad-digest/Dockerfile:1: alpine:3@sha256:0123456789abcdef" \
    "from-bad-digest/Dockerfile:2: alpine:4@sha256:0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"

put from-variable/Dockerfile <<'EOF'
ARG BASE=alpine:3
FROM $BASE
FROM ${BASE} AS second
EOF
expect "FROM with a variable fails" 1 from-variable \
    'from-variable/Dockerfile:2: $BASE' \
    'from-variable/Dockerfile:3: ${BASE}'

put from-platform/base.Dockerfile <<EOF
from --platform=linux/amd64 alpine:3 as build
EOF
expect "FROM with a flag before a tag only reference fails, in any letter case" 1 from-platform \
    "from-platform/base.Dockerfile:1: alpine:3"

put copy-from-image/Dockerfile.dev <<EOF
FROM scratch
COPY --from=busybox:1.36 /bin/busybox /busybox
RUN --mount=type=cache,target=/x --from=docker.io/library/busybox true
EOF
expect "COPY --from with an external image fails" 1 copy-from-image \
    "copy-from-image/Dockerfile.dev:2: busybox:1.36" \
    "copy-from-image/Dockerfile.dev:3: docker.io/library/busybox"

put syntax-line/Dockerfile <<EOF
# syntax=docker/dockerfile:1
FROM scratch
EOF
expect "a syntax line without a digest fails" 1 syntax-line \
    "syntax-line/Dockerfile:1: docker/dockerfile:1"

put compose-tag-only/compose.yaml <<EOF
services:
  postgres:
    image: postgres:17-alpine
EOF
expect "a compose image with a tag only fails" 1 compose-tag-only \
    "compose-tag-only/compose.yaml:3: postgres:17-alpine"

put compose-quoted/docker-compose.override.yml <<EOF
services:
  postgres:
    image: "postgres:17-alpine"   # the database
  cache:
    image: 'redis:8'
  shell:
    image: \${SHELL_REFERENCE}
EOF
expect "a quoted compose image with a tag only fails and is reported without quotes" 1 compose-quoted \
    "compose-quoted/docker-compose.override.yml:3: postgres:17-alpine" \
    "compose-quoted/docker-compose.override.yml:5: redis:8" \
    'compose-quoted/docker-compose.override.yml:7: ${SHELL_REFERENCE}'

put compose-registry-host/compose.yml <<EOF
services:
  postgres:
    image: dokene-mirror.example.com/postgres:17
EOF
expect "a registry host that starts with dokene- is not a local image" 1 compose-registry-host \
    "compose-registry-host/compose.yml:3: dokene-mirror.example.com/postgres:17"

put two-digests/a/Dockerfile <<EOF
FROM quay.io/keycloak/keycloak:26.7.3@sha256:$D1 AS builder
EOF
put two-digests/b/Dockerfile <<EOF
FROM quay.io/keycloak/keycloak:26.7.3@sha256:$D2
EOF
expect "one name and tag with two digests fails on the line that is read second" 1 two-digests \
    "two-digests/b/Dockerfile:1: quay.io/keycloak/keycloak:26.7.3@sha256:$D2"

put java-literal/src/DatabaseTest.java <<EOF
class DatabaseTest {
    // new PostgreSQLContainer("postgres:1") in a comment is skipped
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    static final GenericContainer<?> CACHE = new GenericContainer<>( "redis:8@sha256:$D1");
    static final DockerImageName NAME = DockerImageName.parse("postgres:17");
}
EOF
expect "an image literal in Java fails, pinned or not" 1 java-literal \
    "java-literal/src/DatabaseTest.java:3: postgres:17-alpine" \
    "java-literal/src/DatabaseTest.java:4: redis:8@sha256:$D1" \
    "java-literal/src/DatabaseTest.java:5: postgres:17"

printf '%s\n' \
    '#!/usr/bin/env bash' \
    'JDK_IMAGE=eclipse-temurin:26-jdk' \
    '  local SCAN_IMAGE="${OVERRIDE:-alpine:3}"' \
    "export TOOL_IMAGE='busybox'" | put shell-literal/run.sh
expect "a shell image variable with a tag only literal fails" 1 shell-literal \
    "shell-literal/run.sh:2: eclipse-temurin:26-jdk" \
    "shell-literal/run.sh:3: alpine:3" \
    "shell-literal/run.sh:4: busybox"

# --- Pass cases -----------------------------------------------------------------------------------------------------

put full-pin/Dockerfile <<EOF
FROM --platform=\$BUILDPLATFORM eclipse-temurin:26-jdk@sha256:$D1 AS build
FROM registry.example.com:5000/team/app:1.2.3@sha256:$D2
COPY --from=nginxinc/nginx-unprivileged:1.29-alpine@sha256:$D1 /etc/nginx /etc/nginx
EOF
put full-pin/compose.yaml <<EOF
services:
  postgres:
    image: postgres:17-alpine@sha256:$D1 # pinned
  bridge:
    image: "alpine/socat:1.8.0.3@sha256:$D2"
  java:
    image: eclipse-temurin:26-jdk@sha256:$D1
EOF
expect "a full pin passes, and the same name and tag may repeat with one digest" 0 full-pin

put scratch-and-stages/Dockerfile <<EOF
FROM scratch AS base
FROM base AS Build
FROM build
COPY --from=base /a /a
COPY --from=nginx --chown=1:1 nginx.conf /etc/nginx/conf.d/default.conf
RUN --mount=type=cache,target=/root/.npm true
EOF
expect "scratch, an earlier stage and a stage or named context in --from pass" 0 scratch-and-stages

put local-images/compose.yaml <<EOF
services:
  backend:
    image: dokene-backend:local
  frontend:
    image: dokene-frontend
EOF
printf '%s\n' \
    'IMAGE="dokene-frontend:smoke"' \
    'JDK_IMAGE="${OVERRIDE:-}"' \
    'BASE_IMAGE="$(sed -n 1p Dockerfile)"' \
    'BUILD_IMAGE="$(sed -n 1p \' \
    '  Dockerfile)"' \
    'PINNED_IMAGE=alpine:3@sha256:'"$D1" \
    'IMAGE_NAME=alpine:3' | put local-images/run.sh
expect "images built from this tree and computed or empty shell values pass" 0 local-images

put commented/Dockerfile <<EOF
# FROM alpine:3
  # COPY --from=busybox:1 /a /a
FROM scratch
EOF
put commented/compose.yaml <<EOF
services:
  backend:
    # image: postgres:17
    image: dokene-backend:local
EOF
put commented/Sample.java <<EOF
class Sample {
    // new PostgreSQLContainer("postgres:17")
    /*
     * DockerImageName.parse("postgres:17")
     */
}
EOF
printf '%s\n' '# JDK_IMAGE=eclipse-temurin:26-jdk' 'true' | put commented/run.sh
expect "a commented out line passes" 0 commented

put crlf/Dockerfile <<EOF
FROM alpine:3@sha256:$D1 AS build$(printf '\r')
FROM build$(printf '\r')
EOF
expect "a carriage return at the end of a line is ignored" 0 crlf

put skipped-directories/Dockerfile <<EOF
FROM scratch
EOF
put skipped-directories/node_modules/pkg/Dockerfile <<EOF
FROM alpine:3
EOF
put skipped-directories/build/Dockerfile <<EOF
FROM alpine:3
EOF
put skipped-directories/.worktrees/other/compose.yaml <<EOF
services:
  a:
    image: postgres:17
EOF
expect "node_modules, build and worktree directories are not scanned" 0 skipped-directories

put exempt-helper/ComposeImages.java <<EOF
class ComposeImages { Object x = DockerImageName.parse("postgres@sha256:$D1"); }
EOF
put exempt-helper/ComposeImagesTest.java <<EOF
class ComposeImagesTest { Object x = DockerImageName.parse("postgres:17"); }
EOF
expect "the compose image helper and its test are exempt by file name" 0 exempt-helper

# --- Paths ----------------------------------------------------------------------------------------------------------

expect "a single file can be given as the path" 1 from-tag-only/Dockerfile \
    "from-tag-only/Dockerfile:1: alpine:3"

mkdir "$WORK/cases/empty-dir"
put no-checked-file/README.md <<EOF
FROM alpine:3
EOF
expect_message "a directory with no file exits 2" empty-dir
expect_message "a directory with no file the check reads exits 2" no-checked-file
expect_message "a path that does not exist exits 2" does-not-exist

if [ "$failures" -ne 0 ]; then
    printf '%s check(s) failed\n' "$failures" >&2
    exit 1
fi
echo "all checks passed"
