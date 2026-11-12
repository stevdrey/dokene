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
refute "a second line cannot smuggle a target" is_local_target "$(printf 'http://localhost/\nhttp://evil.example')"
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
refute "a failed eligibility read is not 'no longer due'" not_due "status-500"
refute "an empty status is not 'no longer due'" not_due ""
assert "NOT_YET_DUE is no longer due" not_due NOT_YET_DUE

# --- a record that cannot be written is a failure, not a recorded run
RUN_ID=test BFF_URL=http://localhost:8080 RECORD=/dev/full
refute "writing the record to a full device fails" write_record
RECORD="$TMP/record.yaml"
assert "writing the record to a writable file succeeds" write_record
assert "the record carries the step results" grep -q 'result: failed' "$TMP/record.yaml"

# --- response shapes: an unexpected body is never read as "found" or "zero messages"
printf '{"items":[{"customerId":"c1"},{"customerId":"c2"}],"nextCursor":null}' > "$TMP/q"
assert "a queue page with the customer counts one match" [ "$(queue_match_count "$TMP/q" c1)" = "1" ]
assert "a queue page without the customer counts zero" [ "$(queue_match_count "$TMP/q" c9)" = "0" ]
printf '{"items":null}' > "$TMP/qn"
assert "a queue page with null items is invalid" [ "$(queue_match_count "$TMP/qn" c1)" = "invalid" ]
printf '<html>error</html>' > "$TMP/qh"
assert "a queue body that is not JSON is invalid" [ "$(queue_match_count "$TMP/qh" c1)" = "invalid" ]
printf '{"items":[]}' > "$TMP/m0"
assert "an empty message page counts zero" [ "$(message_count "$TMP/m0")" = "0" ]
assert "a message page with null items is invalid" [ "$(message_count "$TMP/qn")" = "invalid" ]
printf '{"messages":[]}' > "$TMP/mx"
assert "a message page without items is invalid" [ "$(message_count "$TMP/mx")" = "invalid" ]

# --- portable path resolution (no GNU realpath)
mkdir -p "$TMP/real/existing"
assert "a relative path resolves against the current directory" \
  [ "$(cd "$TMP/real" && resolve_path "existing/new/dir")" = "$(cd "$TMP/real" && pwd -P)/existing/new/dir" ]
ln -s "$ROOT_DIR" "$TMP/repo-link"
assert "a symlink into the repository resolves inside it" \
  path_is_within "$(resolve_path "$TMP/repo-link/evidence")" "$(cd "$ROOT_DIR" && pwd -P)"
refute "'..' below a missing directory is refused" resolve_path "$TMP/real/missing/../x"

# --- a stale record from another run never counts as this run's record
mkdir -p "$TMP/stale"
RECORD="$TMP/stale/journey-record.yaml" RUN_ID=older
write_record >/dev/null
chmod a-w "$RECORD"
RUN_ID=newer
assert "a read-only stale record in a writable directory is replaced" write_record
assert "the replaced record carries this run's id" grep -q 'run_id: "newer"' "$RECORD"
RUN_ID=older
write_record >/dev/null
chmod a-w "$RECORD" "$TMP/stale"
RUN_ID=newest
if [ "$(id -u)" -ne 0 ]; then
  refute "a stale record that cannot be replaced fails the run" write_record 2>/dev/null
fi
chmod u+w "$TMP/stale"

# --- origins and ids
refute "an origin with a query is not a plain origin" is_local_url "http://localhost:8080?x=1"
refute "an origin with a fragment is not a plain origin" is_local_url "http://localhost:8080#x"
assert "a UUID is an id" is_id "3fa85f64-5717-4562-b3fc-2c963f66afa6"
refute "an empty value is not an id" is_id ""
refute "null is not an id" is_id "null"

# --- S16 replay: the same real dismissal id in both responses, never "null" against "null"
D1=3fa85f64-5717-4562-b3fc-2c963f66afa6 D2=7c9e6679-7425-40de-944b-e07fc1f90ae7
assert "a replay with the same id passes" same_dismissal 200 "$D1" "$D1"
refute "a replay with a different id fails" same_dismissal 200 "$D1" "$D2"
refute "both ids missing as null fails" same_dismissal 200 null null
refute "both ids empty fails" same_dismissal 200 "" ""
refute "a missing first id fails" same_dismissal 200 null "$D1"
refute "a missing replay id fails" same_dismissal 200 "$D1" null
refute "a replay that is not 200 fails" same_dismissal 201 "$D1" "$D1"

# --- YAML scalars drop other control characters
assert "ESC and BEL are removed from YAML scalars" [ "$(yaml_str "$(printf 'a\033[31mb\007c')")" = '"a[31mbc"' ]

# --- the inside-repository guard compares directory identity, not only strings
assert "an ancestor that is the repository by identity is detected" path_is_within_dir "$ROOT_DIR/./missing/out" "$ROOT_DIR"
refute "a scratch directory is not the repository by identity" path_is_within_dir "$TMP/real/out" "$ROOT_DIR"

# --- the fixture-only mode for the manual browser step is documented
assert "--fixture-only appears in the usage text" grep -q -- '--fixture-only' "$SCRIPT"

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
