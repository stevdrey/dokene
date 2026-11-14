#!/usr/bin/env bash
# ABOUTME: Smoke test for the login start rate limit of the frontend image (issue #311, audit M2).
# ABOUTME: Builds the image, runs it without a backend, and checks which requests nginx answers with 429.
#
# No backend runs here, so a login start that nginx passes on answers 502: "not 429" means "passed on". The burst is
# sent at the same moment, so 1 + burst (11) are passed on. One more slot frees every 2 seconds, so a burst that a
# slow runner takes that long to deliver passes one more; the check allows up to 2 more. That refused requests never
# reach the backend is shown on the dev stack by scripts/verify-issue-311-anonymous-sessions.sh. Needs bash, curl 7.83
# or newer and a running Docker daemon; it never touches the Compose stack.
#
# Behind a trusted outer proxy (issue #320, spec 0018) the limit keys on the client address the edge restores from that
# proxy's X-Forwarded-For. The last checks restart the edge with a trusted-proxies.conf naming this host's peer address,
# read from the edge's access log, and show that two restored addresses get separate buckets.
#
# Usage: ./scripts/tests/nginx-login-rate-limit-smoke.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE="dokene-frontend:nginx-login-limit-smoke"
NAME="dokene-nginx-login-limit-smoke-$$"
REQUESTS=40
PASSED_AT_ONCE=11
PASSED_AT_MOST=13
REFUSAL_BODY="Too many sign in attempts. Try again in a moment."
WORK="$(mktemp -d)"
failures=0

pass() { printf '[pass] %s\n' "$1"; }
fail() { printf '[FAIL] %s\n' "$1" >&2; failures=$((failures + 1)); }
check() {
  # check "description" command...
  local description="$1"; shift
  if "$@" >/dev/null 2>&1; then pass "$description"; else fail "$description"; fi
}

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1; docker rmi "$IMAGE" >/dev/null 2>&1; rm -rf "$WORK"; }
trap cleanup EXIT

docker build -q --build-context "nginx=$ROOT_DIR/infra/docker/frontend" \
  -f "$ROOT_DIR/infra/docker/frontend/Dockerfile" -t "$IMAGE" "$ROOT_DIR/frontend" >/dev/null \
  || { echo "image build failed" >&2; exit 1; }

# start_edge [docker run options...]: (re)starts the edge, with fresh limit zones, and waits until it serves.
start_edge() {
  docker rm -f "$NAME" >/dev/null 2>&1
  docker run -d --name "$NAME" -p 127.0.0.1::8080 "$@" "$IMAGE" >/dev/null \
    || { echo "container start failed" >&2; exit 1; }
  PORT="$(docker port "$NAME" 8080/tcp | head -1 | sed 's/.*://')"
  BASE="http://127.0.0.1:$PORT"
  for _ in $(seq 1 30); do
    curl -fs -o /dev/null "$BASE/" && break
    sleep 1
  done
}

# burst "<path>" [curl options...]: sends $REQUESTS requests at the same moment and prints every body, plus one summary
# line per answer: "<status>|<Retry-After>|<Content-Type>|<X-Content-Type-Options>|<Set-Cookie>".
burst() {
  local path="$1" args=() i
  shift
  for i in $(seq 1 "$REQUESTS"); do
    args+=(-o - "$BASE$path")
  done
  curl -s -m 20 --parallel --parallel-immediate --parallel-max "$REQUESTS" "$@" \
    -w '\n%{http_code}|%header{retry-after}|%header{content-type}|%header{x-content-type-options}|%header{set-cookie}\n' \
    "${args[@]}"
}
passed_on() { echo "$(($(answers "$1") - $(refusals "$1")))"; }

start_edge
answers() { grep -cE '^[0-9]{3}\|' <<<"$1"; }
refusals() { grep -c '^429|' <<<"$1"; }
# every_refusal_is "<burst output>" "<the whole expected line of a refusal>"
every_refusal_is() { ! grep '^429|' <<<"$1" | grep -qvxF "$2"; }

login="$(burst /oauth2/authorization/dokene)"
refused="$(refusals "$login")"
passed="$(($(answers "$login") - refused))"
check "every one of the $REQUESTS login starts sent at the same moment was answered" \
  test "$(answers "$login")" -eq "$REQUESTS"
check "$PASSED_AT_ONCE to $PASSED_AT_MOST are passed on and the rest are refused with 429 (got $passed and $refused)" \
  test "$passed" -ge "$PASSED_AT_ONCE" -a "$passed" -le "$PASSED_AT_MOST"
check "every refusal sends Retry-After: 2, text/plain, nosniff and no cookie" \
  every_refusal_is "$login" "429|2|text/plain|nosniff|"
check "every refusal carries the short text body" test "$(grep -cxF "$REFUSAL_BODY" <<<"$login")" -eq "$refused"

# The limit is keyed on login starts only: the same burst on other proxied routes is never refused.
check "$REQUESTS requests to /api/session at the same moment get no 429" \
  test "$(refusals "$(burst /api/session)")" -eq 0
check "$REQUESTS login callbacks at the same moment get no 429" \
  test "$(refusals "$(burst '/login/oauth2/code/dokene?code=x&state=y')")" -eq 0

# two_clients: sends a login start burst from client 203.0.113.1 and one from 203.0.113.2 at the same moment, and prints
# "<passed on for the first> <passed on for the second>". The bursts must overlap: a passed on request waits about 30
# seconds for the missing backend, and by then a bucket has refilled.
two_clients() {
  burst /oauth2/authorization/dokene -H 'X-Forwarded-For: 203.0.113.1' >"$WORK/first" &
  burst /oauth2/authorization/dokene -H 'X-Forwarded-For: 203.0.113.2' >"$WORK/second"
  wait
  echo "$(passed_on "$(cat "$WORK/first")") $(passed_on "$(cat "$WORK/second")")"
}
# within_one_bucket "<count>": what a single fresh bucket passes on.
within_one_bucket() { test "$1" -ge "$PASSED_AT_ONCE" -a "$1" -le "$PASSED_AT_MOST"; }

# Untrusted peer (the shipped trusted-proxies.conf): the client addresses are ignored, so both share the peer's bucket.
read -r first second <<<"$(two_clients)"
check "untrusted peer: two forged client addresses share one bucket ($first + $second passed on)" \
  within_one_bucket "$((first + second))"

# Trusted peer: the edge's access log starts with the client address, here this host's peer.
PEER="$(docker logs "$NAME" 2>/dev/null | awk '$1 ~ /^[0-9a-fA-F.:]+$/ { address = $1 } END { print address }')"
check "the access log shows this host's peer address (got '$PEER')" grep -qE '^[0-9a-fA-F.:]+$' <<<"$PEER"
printf 'set_real_ip_from %s;\n' "$PEER" >"$WORK/trusted-proxies.conf"
chmod 644 "$WORK/trusted-proxies.conf"
start_edge -v "$WORK/trusted-proxies.conf:/etc/nginx/snippets/trusted-proxies.conf:ro"

read -r first second <<<"$(two_clients)"
check "trusted peer: client 203.0.113.1 gets its own bucket, the repeats get 429 (passed on $first)" \
  within_one_bucket "$first"
check "trusted peer: client 203.0.113.2 at the same moment gets its own bucket too (passed on $second)" \
  within_one_bucket "$second"

if [ "$failures" -ne 0 ]; then
  printf '%s check(s) failed\n' "$failures" >&2
  exit 1
fi
echo "all checks passed"
