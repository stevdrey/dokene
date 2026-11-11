#!/usr/bin/env bash
# ABOUTME: Smoke test for the login start rate limit of the frontend image (issue #311, audit M2).
# ABOUTME: Builds the image, runs it without a backend, and checks which requests nginx answers with 429.
#
# No backend runs here, so a login start that nginx passes on answers 502: "not 429" means "passed on". The burst is
# sent at the same moment, so exactly 1 + burst (11) are passed on. That refused requests never reach the backend is
# shown on the dev stack by scripts/verify-issue-311-anonymous-sessions.sh. Needs bash, curl 7.83 or newer and a
# running Docker daemon; it never touches the Compose stack.
#
# Usage: ./scripts/tests/nginx-login-rate-limit-smoke.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE="dokene-frontend:nginx-login-limit-smoke"
NAME="dokene-nginx-login-limit-smoke-$$"
REQUESTS=40
REFUSAL_BODY="Too many sign in attempts. Try again in a moment."
failures=0

pass() { printf '[pass] %s\n' "$1"; }
fail() { printf '[FAIL] %s\n' "$1" >&2; failures=$((failures + 1)); }
check() {
  # check "description" command...
  local description="$1"; shift
  if "$@" >/dev/null 2>&1; then pass "$description"; else fail "$description"; fi
}

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1; docker rmi "$IMAGE" >/dev/null 2>&1; }
trap cleanup EXIT

docker build -q --build-context "nginx=$ROOT_DIR/infra/docker/frontend" \
  -f "$ROOT_DIR/infra/docker/frontend/Dockerfile" -t "$IMAGE" "$ROOT_DIR/frontend" >/dev/null \
  || { echo "image build failed" >&2; exit 1; }
docker run -d --name "$NAME" -p 127.0.0.1::8080 "$IMAGE" >/dev/null || { echo "container start failed" >&2; exit 1; }
PORT="$(docker port "$NAME" 8080/tcp | head -1 | sed 's/.*://')"
BASE="http://127.0.0.1:$PORT"

for _ in $(seq 1 30); do
  curl -fs -o /dev/null "$BASE/" && break
  sleep 1
done

# burst "<path>": sends $REQUESTS requests at the same moment and prints every body, plus one summary line per answer:
# "<status>|<Retry-After>|<Content-Type>|<X-Content-Type-Options>|<Set-Cookie>".
burst() {
  local args=() i
  for i in $(seq 1 "$REQUESTS"); do
    args+=(-o - "$BASE$1")
  done
  curl -s -m 20 --parallel --parallel-immediate --parallel-max "$REQUESTS" \
    -w '\n%{http_code}|%header{retry-after}|%header{content-type}|%header{x-content-type-options}|%header{set-cookie}\n' \
    "${args[@]}"
}
answers() { grep -cE '^[0-9]{3}\|' <<<"$1"; }
refusals() { grep -c '^429|' <<<"$1"; }
# every_refusal_is "<burst output>" "<the whole expected line of a refusal>"
every_refusal_is() { ! grep '^429|' <<<"$1" | grep -qvxF "$2"; }

login="$(burst /oauth2/authorization/dokene)"
refused="$(refusals "$login")"
check "of $REQUESTS login starts at the same moment, 29 are refused with 429 (got $refused)" test "$refused" -eq 29
check "the other 11 are passed on to the backend" test "$(($(answers "$login") - refused))" -eq 11
check "every refusal sends Retry-After: 2, text/plain, nosniff and no cookie" \
  every_refusal_is "$login" "429|2|text/plain|nosniff|"
check "every refusal carries the short text body" test "$(grep -cxF "$REFUSAL_BODY" <<<"$login")" -eq "$refused"

# The limit is keyed on login starts only: the same burst on other proxied routes is never refused.
check "$REQUESTS requests to /api/session at the same moment get no 429" \
  test "$(refusals "$(burst /api/session)")" -eq 0
check "$REQUESTS login callbacks at the same moment get no 429" \
  test "$(refusals "$(burst '/login/oauth2/code/dokene?code=x&state=y')")" -eq 0

if [ "$failures" -ne 0 ]; then
  printf '%s check(s) failed\n' "$failures" >&2
  exit 1
fi
echo "all checks passed"
