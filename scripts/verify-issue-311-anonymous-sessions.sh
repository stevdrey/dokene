#!/usr/bin/env bash
# ABOUTME: Evidence run for issue #311 (audit M2) against the local dev stack: anonymous requests create no server
# ABOUTME: session, and login starts are bounded by the nginx rate limit. Prints counts and heap readings only.
#
# Sends synthetic anonymous requests through the nginx origin and counts the session cookies and 429 answers it gets
# back. Output is sanitized: it never prints a cookie, a token or a session id. Used heap is read with
# `jcmd 1 GC.heap_info` right after `jcmd 1 GC.run`; the runtime image is a JRE without jcmd, so jcmd runs from a JDK
# image that joins the backend container's process namespace. Needs bash, curl 7.83 or newer, git and Docker, and the
# stack from `./scripts/dev-env.sh up`.
#
# Usage: ./scripts/verify-issue-311-anonymous-sessions.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="${DOKENE_VERIFY_ORIGIN:-http://localhost:5173}"
JDK_IMAGE="${DOKENE_VERIFY_JDK_IMAGE:-eclipse-temurin:26-jdk}"
SWEEP_REQUESTS=2000
SWEEP_PARALLEL=20
LOGIN_STARTS=40
# 1 + burst pass at once. One more slot frees every 2 seconds, so a burst that a slow machine takes that long to
# deliver passes one more; the check allows up to 2 more.
SESSIONS_AT_ONCE=11
SESSIONS_AT_MOST=13
# The sweep includes login start paths, which use up the burst. Its 10 slots free at one per 2 seconds.
BURST_REFILL_SECONDS=25
failures=0

pass() { printf '[pass] %s\n' "$1"; }
fail() { printf '[FAIL] %s\n' "$1" >&2; failures=$((failures + 1)); }
check() {
  # check "description" command...
  local description="$1"; shift
  if "$@" >/dev/null 2>&1; then pass "$description"; else fail "$description"; fi
}

command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "docker is required" >&2; exit 1; }
BACKEND="$(cd "$ROOT_DIR" && docker compose ps -q backend 2>/dev/null)"
[ -n "$BACKEND" ] || { echo "the backend container is not running; start it with ./scripts/dev-env.sh up" >&2; exit 1; }
[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/session")" = "401" ] \
  || { echo "$BASE/api/session did not answer 401; is the stack up?" >&2; exit 1; }

REVISION="$(git -C "$ROOT_DIR" rev-parse HEAD)"
if [ -n "$(git -C "$ROOT_DIR" status --porcelain --untracked-files=no)" ]; then
  REVISION="$REVISION plus uncommitted changes (not valid as recorded evidence)"
fi

jcmd_backend() {
  docker run --rm --pid="container:$BACKEND" --user "$(docker exec "$BACKEND" id -u):$(docker exec "$BACKEND" id -g)" \
    "$JDK_IMAGE" jcmd 1 "$1" 2>/dev/null
}
# Used heap in KiB, read right after a full collection so that only live objects count.
used_heap_kib() {
  jcmd_backend GC.run >/dev/null
  jcmd_backend GC.heap_info | grep -oE 'used [0-9]+K' | head -1 | grep -oE '[0-9]+'
}
container_memory() { docker stats --no-stream --format '{{.MemUsage}}' "$BACKEND" | sed 's/ \/.*//'; }

# request "<method>" "<path>" ["<header>" ...]: one block of curl configuration for one request.
request() {
  local method="$1" path="$2" header; shift 2
  printf 'next\nurl = "%s%s"\noutput = "/dev/null"\nwrite-out = "%%{http_code}|%%header{set-cookie}\\n"\n' "$BASE" "$path"
  if [ "$method" = "HEAD" ]; then printf 'head\n'; else printf 'request = "%s"\n' "$method"; fi
  for header in "$@"; do printf 'header = "%s"\n' "$header"; done
}
MADE_UP_SESSION="Cookie: JSESSIONID=made-up-session-id"
MADE_UP_CSRF="X-CSRF-TOKEN: made-up-csrf-token"
# The anonymous sweep of spec 0016, without the routes nginx serves itself (/, /error, /unknown-route) and without
# the raw health check request, which the backend integration test covers.
sweep_route() {
  case "$1" in
    0) request GET /api/session "Accept: text/html" ;;
    1) request GET /api/session "Accept: application/json" ;;
    2) request GET /api/session "$MADE_UP_SESSION" ;;
    3) request HEAD /api/session ;;
    4) request OPTIONS /api/customers "Origin: http://localhost:5173" "Access-Control-Request-Method: POST" ;;
    5) request GET /api/customers ;;
    6) request GET /api/tenants ;;
    7) request GET /api/unknown-route ;;
    8) request GET /logout ;;
    9) request POST /api/customers "$MADE_UP_SESSION" ;;
    10) request POST /logout ;;
    11) request POST /logout/connect/back-channel/dokene "Content-Type: application/x-www-form-urlencoded" ;;
    12) request GET "/login/oauth2/code/dokene?code=x&state=y" ;;
    13) request GET /oauth2/authorization/unknown ;;
    14) request GET /login/x ;;
    15) request GET /oauth2/x ;;
    16) request POST /login/oauth2/code/dokene ;;
    17) request POST /oauth2/authorization/dokene ;;
    18) request POST /api/customers ;;
    19) request POST /api/customers "$MADE_UP_CSRF" ;;
    20) request PUT /api/customers ;;
    21) request PUT /api/customers "$MADE_UP_CSRF" ;;
    22) request PATCH /api/customers ;;
    23) request PATCH /api/customers "$MADE_UP_CSRF" ;;
    24) request DELETE /api/customers ;;
    25) request DELETE /api/customers "$MADE_UP_CSRF" ;;
    26) request POST /api/unknown-route ;;
    27) request POST /api/unknown-route "$MADE_UP_CSRF" ;;
    28) request PUT /api/unknown-route ;;
    29) request PUT /api/unknown-route "$MADE_UP_CSRF" ;;
    30) request PATCH /api/unknown-route ;;
    31) request PATCH /api/unknown-route "$MADE_UP_CSRF" ;;
    32) request DELETE /api/unknown-route ;;
    33) request DELETE /api/unknown-route "$MADE_UP_CSRF" ;;
  esac
}
SWEEP_ROUTES=34

# Each answer is one line, "<status>|<Set-Cookie>". Only counts leave these functions.
answers() { grep -cE '^[0-9]{3}\|' <<<"$1"; }
session_cookies() { grep -cE '^[0-9]{3}\|JSESSIONID=[^;]' <<<"$1"; }
refusals() { grep -c '^429|' <<<"$1"; }
refusals_with_cookie() { grep -cE '^429\|.' <<<"$1"; }

echo "== Issue 311: anonymous session creation, evidence run"
echo "tested revision: $REVISION"
echo "entry point: $BASE"

heap_start="$(used_heap_kib)"
memory_start="$(container_memory)"
[ -n "$heap_start" ] || { echo "could not read the backend heap with jcmd" >&2; exit 1; }

sweep="$(i=0; while [ "$i" -lt "$SWEEP_REQUESTS" ]; do sweep_route $((i % SWEEP_ROUTES)); i=$((i + 1)); done \
  | curl -s -m 300 --parallel --parallel-max "$SWEEP_PARALLEL" -K -)"
heap_after_sweep="$(used_heap_kib)"
memory_after_sweep="$(container_memory)"
[ -n "$heap_after_sweep" ] || { echo "could not read the backend heap with jcmd after the sweep" >&2; exit 1; }

echo "== Anonymous sweep"
echo "requests sent: $SWEEP_REQUESTS over $SWEEP_ROUTES routes"
echo "answers received: $(answers "$sweep")"
echo "session cookies issued: $(session_cookies "$sweep")"
echo "429 answers (login start paths in the sweep, refused by nginx): $(refusals "$sweep")"
check "every sweep request was answered" test "$(answers "$sweep")" -eq "$SWEEP_REQUESTS"
check "the sweep issued no session cookie" test "$(session_cookies "$sweep")" -eq 0

sleep "$BURST_REFILL_SECONDS"
burst="$(i=0; while [ "$i" -lt "$LOGIN_STARTS" ]; do request GET /oauth2/authorization/dokene; i=$((i + 1)); done \
  | curl -s -m 60 --parallel --parallel-immediate --parallel-max "$LOGIN_STARTS" -K -)"
heap_after_burst="$(used_heap_kib)"
memory_after_burst="$(container_memory)"
[ -n "$heap_after_burst" ] || { echo "could not read the backend heap with jcmd after the login burst" >&2; exit 1; }

echo "== Login burst"
echo "login starts sent at the same moment: $LOGIN_STARTS"
echo "session cookies issued: $(session_cookies "$burst")"
echo "429 answers: $(refusals "$burst")"
echo "429 answers that set a cookie: $(refusals_with_cookie "$burst")"
check "$SESSIONS_AT_ONCE to $SESSIONS_AT_MOST login starts got a session" \
  test "$(session_cookies "$burst")" -ge "$SESSIONS_AT_ONCE" -a "$(session_cookies "$burst")" -le "$SESSIONS_AT_MOST"
check "every other login start was refused with 429" \
  test "$(($(session_cookies "$burst") + $(refusals "$burst")))" -eq "$LOGIN_STARTS"
check "no refusal set a cookie, so none reached the backend" test "$(refusals_with_cookie "$burst")" -eq 0

echo "== Backend memory"
echo "used heap after a full collection (KiB): start $heap_start, after the sweep $heap_after_sweep," \
  "after the login burst $heap_after_burst"
echo "used heap growth over the sweep (KiB): $((heap_after_sweep - heap_start))"
echo "used heap growth over the login burst (KiB): $((heap_after_burst - heap_after_sweep))"
echo "container memory (docker stats): start $memory_start, after the sweep $memory_after_sweep," \
  "after the login burst $memory_after_burst"

if [ "$failures" -ne 0 ]; then
  printf '%s check(s) failed\n' "$failures" >&2
  exit 1
fi
echo "all checks passed"
