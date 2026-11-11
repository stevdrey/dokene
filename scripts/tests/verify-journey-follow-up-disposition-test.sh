#!/usr/bin/env bash
# Offline tests for scripts/verify-journey-follow-up-disposition.sh (Issue #375).
# They source the script's pure helpers and inspect its text; no stack, network or Docker is used.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/verify-journey-follow-up-disposition.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

FAILURES=0
pass() { printf 'ok   %s\n' "$1"; }
fail() { printf 'FAIL %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
assert() { local name="$1"; shift; if "$@"; then pass "$name"; else fail "$name"; fi; }
refute() { local name="$1"; shift; if "$@"; then fail "$name"; else pass "$name"; fi; }

# shellcheck source=../verify-journey-follow-up-disposition.sh
. "$SCRIPT"

# --- the journey only targets loopback origins
assert "localhost with port is local" is_local_url "http://localhost:8080"
assert "127.0.0.1 with port is local" is_local_url "http://127.0.0.1:8081"
assert "bare localhost is local" is_local_url "http://localhost"
refute "https is not accepted" is_local_url "https://localhost:8080"
refute "a remote host is not local" is_local_url "http://staging.example.com"
refute "a host that starts with localhost is not local" is_local_url "http://localhost.example.com"
refute "a port cannot carry a hostname" is_local_url "http://localhost:8080.example.com"
refute "userinfo cannot smuggle a host" is_local_url "http://localhost:8080@example.com"
refute "a path is not an origin" is_local_url "http://localhost:8080/api/x"
refute "an empty value is not local" is_local_url ""

# --- credentials are only posted to loopback targets
assert "a loopback login form is a local target" is_local_target "http://localhost:8081/realms/dokene/login-actions/authenticate?x=1"
refute "a remote login form is not a local target" is_local_target "http://login.example.com/realms/dokene"
refute "a host that starts with localhost is not a local target" is_local_target "http://localhost.example.com/x"
refute "userinfo cannot smuggle a target" is_local_target "http://localhost:8081@example.com/x"

# --- evidence stays outside the candidate worktree
assert "the root itself is within" path_is_within "/repo" "/repo"
assert "a child is within" path_is_within "/repo/docs/x" "/repo"
refute "a sibling with a shared prefix is not within" path_is_within "/repo-evidence" "/repo"
refute "a scratch directory is not within" path_is_within "/tmp/run" "/repo"

# --- YAML scalars cannot break the record
assert "quotes and backslashes are escaped" [ "$(yaml_str 'a"b\c')" = '"a\"b\\c"' ]
assert "newlines and tabs are flattened" [ "$(yaml_str "$(printf 'a\nb\tc')")" = '"a b c"' ]

# --- headers are matched case-insensitively and the last one wins
printf 'HTTP/1.1 302 Found\r\nLocation: http://first\r\n\r\nHTTP/1.1 200 OK\r\nETag: "7"\r\nlocation: http://second\r\nX-Request-Id: abc\r\n' > "$TMP/h"
assert "ETag is read with its quotes" [ "$(header_value etag "$TMP/h")" = '"7"' ]
assert "the last Location wins" [ "$(header_value Location "$TMP/h")" = "http://second" ]
assert "a missing header is empty" [ -z "$(header_value x-missing "$TMP/h")" ]

# --- step bookkeeping
check T1 "passing check" "1" "1" eq 1 1 >/dev/null
check T2 "failing check" "1" "2" eq 1 2 > "$TMP/out"
record_step T3 "manual step" "x" "not executed" not-run manual >/dev/null
assert "counters track pass, fail and not-run" [ "$STEPS_PASSED:$STEPS_FAILED:$STEPS_NOT_RUN" = "1:1:1" ]
assert "a failure prints expected and actual" grep -q 'expected: 1' "$TMP/out"
assert "the record lists the failed step" grep -q 'result: failed' <<<"$STEPS_YAML"
assert "a not-run step keeps its kind" grep -q 'kind: manual' <<<"$STEPS_YAML"
refute "an unknown result is refused" record_step T4 "x" "x" "x" maybe 2>/dev/null
assert "due means DUE or OVERDUE" is_due OVERDUE
refute "an unavailable status is not 'no longer due'" not_due "null"

# --- static safety: no outbound or provider write path, no remote target
refute "the journey never calls a message send, approval or creation path" \
  grep -Eq '/sends|/approvals|POST "?/api/customers/[^ ]*/messages|graph\.facebook|api\.openai' "$SCRIPT"
# shellcheck disable=SC2016 # the pattern is literal text to find in the script
refute "the journey never prints a response body" grep -Eq 'cat "\$WORK_DIR/body"' "$SCRIPT"

# --- an --out inside the repository is refused before anything is written there
OUT_PROBE="$ROOT_DIR/.journey-out-probe-$$"
"$SCRIPT" doctor --out "$OUT_PROBE" >/dev/null 2>&1
assert "--out inside the repository exits 2" [ "$?" -eq 2 ]
refute "--out inside the repository creates nothing" [ -e "$OUT_PROBE" ]
rm -rf "$OUT_PROBE"

if [ "$FAILURES" -gt 0 ]; then printf '\n%s test(s) failed\n' "$FAILURES"; exit 1; fi
printf '\nall tests passed\n'
