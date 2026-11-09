#!/usr/bin/env bash
# ABOUTME: Tests scripts/check-workflow-pins.sh against the real workflows and one fixture directory per rule (issue #309, audit M5).
# ABOUTME: Compares exit codes and the exact `file:line: reference` output; needs only bash, no network, no Docker.
#
# Usage: ./scripts/tests/check-workflow-pins-test.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHECK="$ROOT_DIR/scripts/check-workflow-pins.sh"
FIXTURES="scripts/tests/fixtures/check-workflow-pins"
failures=0

cd "$ROOT_DIR" || exit 1

WORK="$(mktemp -d)"
trap '[ -n "$WORK" ] && rm -rf -- "$WORK"' EXIT

# expect "description" <expected exit> <dir> [expected output lines...]
expect() {
    local description="$1" want_exit="$2" dir="$3"; shift 3
    local got_exit=0
    "$CHECK" "$dir" >"$WORK/out" 2>"$WORK/err" || got_exit=$?
    printf '%s\n' "$@" >"$WORK/want"
    [ "$#" -eq 0 ] && : >"$WORK/want"
    if [ "$got_exit" -eq "$want_exit" ] && cmp -s "$WORK/out" "$WORK/want"; then
        printf '[pass] %s\n' "$description"
    else
        printf '[FAIL] %s (exit %s, wanted %s)\n' "$description" "$got_exit" "$want_exit" >&2
        sed 's/^/       stdout: /' "$WORK/out" >&2
        sed 's/^/       stderr: /' "$WORK/err" >&2
        failures=$((failures + 1))
    fi
}

SHA=0123456789abcdef0123456789abcdef01234567
SHA2=89abcdef0123456789abcdef0123456789abcdef

expect "the real workflows are all pinned" 0 .github/workflows

default_dir_is_the_workflows_dir() {
    "$CHECK" >"$WORK/default-out" 2>/dev/null; local a=$?
    "$CHECK" .github/workflows >"$WORK/explicit-out" 2>/dev/null; local b=$?
    [ "$a" -eq "$b" ] && cmp -s "$WORK/default-out" "$WORK/explicit-out"
}
if default_dir_is_the_workflows_dir; then
    printf '[pass] %s\n' "the default directory is .github/workflows"
else
    printf '[FAIL] %s\n' "the default directory is .github/workflows" >&2
    failures=$((failures + 1))
fi

expect "a tag reference fails" 1 "$FIXTURES/tag" \
    "$FIXTURES/tag/workflow.yml:6: actions/checkout@v7"
expect "a branch reference fails" 1 "$FIXTURES/branch" \
    "$FIXTURES/branch/workflow.yml:6: actions/checkout@main"
expect "a short SHA fails" 1 "$FIXTURES/short-sha" \
    "$FIXTURES/short-sha/workflow.yml:6: actions/checkout@0123456"
expect "an uppercase SHA fails" 1 "$FIXTURES/uppercase-sha" \
    "$FIXTURES/uppercase-sha/workflow.yml:6: actions/checkout@0123456789ABCDEF0123456789ABCDEF01234567"
expect "a full SHA with no comment fails" 1 "$FIXTURES/sha-no-comment" \
    "$FIXTURES/sha-no-comment/workflow.yml:6: actions/checkout@$SHA"
expect "a full SHA with a prose comment fails" 1 "$FIXTURES/sha-prose-comment" \
    "$FIXTURES/sha-prose-comment/workflow.yml:6: actions/checkout@$SHA"
expect "a quoted tag fails and is reported without quotes" 1 "$FIXTURES/quoted-tag" \
    "$FIXTURES/quoted-tag/workflow.yml:6: actions/checkout@v7"
expect "a job level reusable workflow on a tag fails" 1 "$FIXTURES/reusable-workflow-tag" \
    "$FIXTURES/reusable-workflow-tag/workflow.yml:5: owner/repo/.github/workflows/reusable.yml@v2"
expect "a .yaml file is scanned" 1 "$FIXTURES/yaml-extension" \
    "$FIXTURES/yaml-extension/workflow.yaml:6: actions/setup-node@v7"
expect "two violations in one file are both reported" 1 "$FIXTURES/two-violations" \
    "$FIXTURES/two-violations/workflow.yml:6: actions/checkout@v7" \
    "$FIXTURES/two-violations/workflow.yml:9: actions/setup-node@v7"
expect "two SHAs for one repository report the second" 1 "$FIXTURES/two-shas" \
    "$FIXTURES/two-shas/workflow.yml:7: actions/cache/save@$SHA2"
expect "a docker:// reference without a digest fails" 1 "$FIXTURES/docker-no-digest" \
    "$FIXTURES/docker-no-digest/workflow.yml:6: docker://alpine:3.20"
expect "an empty directory exits 2" 2 "$FIXTURES/empty-dir"

expect "a local ./ action passes" 0 "$FIXTURES/local-action"
expect "a docker:// reference with a 64 hex digest passes" 0 "$FIXTURES/docker-digest"
expect "a commented out uses line is skipped" 0 "$FIXTURES/commented-out"

if [ "$failures" -gt 0 ]; then
    echo "$failures check(s) failed" >&2
    exit 1
fi
echo "All checks passed"
