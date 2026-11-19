#!/usr/bin/env bash
# ABOUTME: Smoke test for the frontend image's logs (issue #330): no query string, credential or PII reaches them,
# ABOUTME: on success and on failure paths, while every request still leaves one sanitized access log line.
#
# Sends requests whose query strings and Referer carry synthetic values of the sensitive kinds the edge sees: the Meta
# webhook verify token and challenge, an OAuth callback code and state, and customer search terms (a phone number and a
# name). Every value is a fresh random marker made by this run; none is a real credential. The requests go through the
# edge in four states: no backend at all (the name does not resolve), a backend name with nothing listening (connection
# refused), a webhook body over the 4 MiB cap (413), and a stub backend that answers 200. After each state the edge's
# whole output, stdout and stderr, must hold none of the markers, and the access log must still show each request's
# path, status and upstream status. The stub and the stand-in backend run from the same image, so the script names no
# image of its own. Needs bash, curl and a running Docker daemon; it never touches the Compose stack.
#
# Usage: ./scripts/tests/nginx-log-redaction-smoke.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# The markers travel in URLs and headers; the wrapper hands those to curl in a config on a file descriptor (issue 327).
# shellcheck source=../lib/curl-protected.sh
source "$ROOT_DIR/scripts/lib/curl-protected.sh"
# shellcheck source=../lib/secret-match.sh
source "$ROOT_DIR/scripts/lib/secret-match.sh"

IMAGE="dokene-frontend:nginx-log-smoke"
NAME="dokene-nginx-log-smoke-$$"
BACKEND="dokene-nginx-log-smoke-backend-$$"
NET="dokene-nginx-log-smoke-$$"
NGINX_CONF="$ROOT_DIR/infra/docker/frontend/nginx.conf"
WORK="$(mktemp -d)"
failures=0

pass() { printf '[pass] %s\n' "$1"; }
fail() { printf '[FAIL] %s\n' "$1" >&2; failures=$((failures + 1)); }
check() {
  # check "description" command...
  local description="$1"; shift
  if "$@" >/dev/null 2>&1; then pass "$description"; else fail "$description"; fi
}

cleanup() {
  docker rm -f "$NAME" "$BACKEND" >/dev/null 2>&1
  docker network rm "$NET" >/dev/null 2>&1
  docker rmi "$IMAGE" >/dev/null 2>&1
  rm -rf "$WORK"
}
trap cleanup EXIT

# marker <label>: a fresh value no log line can hold by chance, URL safe so it travels unencoded.
marker() { printf '%s%s\n' "$1" "$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n')"; }
VERIFY_TOKEN="$(marker vt)"
CHALLENGE="$(marker ch)"
AUTH_CODE="$(marker code)"
AUTH_STATE="$(marker state)"
PHONE="5068$(od -An -N4 -tu4 /dev/urandom | tr -d ' \n')"
CUSTOMER_NAME="$(marker name)"
REFERER_TERM="$(marker ref)"
SPA_TERM="$(marker spa)"
MARKERS=("$VERIFY_TOKEN" "$CHALLENGE" "$AUTH_CODE" "$AUTH_STATE" "$PHONE" "$CUSTOMER_NAME" "$REFERER_TERM" "$SPA_TERM")

# Static checks, no Docker. The base image's nginx.conf logs "$request" (the request line, query string included) at
# http level. An access_log at that same level would add a second log rather than replace it, so the redacted one is
# set inside the server block, and it is the only one.
check "nginx.conf sets exactly one access_log, with the redacted format" \
  test "$(grep -cE '^[[:space:]]*access_log[[:space:]]' "$NGINX_CONF")" -eq 1 -a \
  "$(grep -cE '^[[:space:]]*access_log[[:space:]]+/dev/stdout[[:space:]]+redacted;' "$NGINX_CONF")" -eq 1
check "nginx.conf sets exactly one error_log, the request scoped one, and it writes nothing" \
  test "$(grep -cE '^[[:space:]]*error_log[[:space:]]' "$NGINX_CONF")" -eq 1 -a \
  "$(grep -cE '^[[:space:]]*error_log[[:space:]]+/dev/null;' "$NGINX_CONF")" -eq 1
# The nginx variables that hold a query string, the raw request line, a cookie, a credential header or a body.
UNSAFE_VARIABLES='[$](request|request_uri|args|arg_[a-z0-9_]+|query_string|is_args|uri|http_referer|http_cookie'
UNSAFE_VARIABLES+='|cookie_[a-z0-9_]+|http_authorization|http_proxy_authorization|remote_user|upstream_http_[a-z0-9_]+'
UNSAFE_VARIABLES+='|request_body)([^a-z0-9_]|$)'
format_is_safe() {
  local format
  format="$(sed -n '/^log_format redacted/,/;/p' "$NGINX_CONF")"
  [ -n "$format" ] && ! grep -qE "$UNSAFE_VARIABLES" <<<"$format"
}
check "the redacted format names no variable that carries a query string, a cookie or a credential header" \
  format_is_safe

# The stub backend answers 200 to every request.
printf 'server {\n    listen 8080;\n    location / {\n        default_type text/plain;\n        return 200 "ok\\n";\n    }\n}\n' \
  >"$WORK/stub.conf"
chmod 644 "$WORK/stub.conf"

docker build -q --build-context "nginx=$ROOT_DIR/infra/docker/frontend" \
  -f "$ROOT_DIR/infra/docker/frontend/Dockerfile" -t "$IMAGE" "$ROOT_DIR/frontend" >/dev/null \
  || { echo "image build failed" >&2; exit 1; }
docker network create "$NET" >/dev/null || { echo "network create failed" >&2; exit 1; }
docker run -d --name "$NAME" --network "$NET" -p 127.0.0.1::8080 "$IMAGE" >/dev/null \
  || { echo "edge container start failed" >&2; exit 1; }
PORT="$(docker port "$NAME" 8080/tcp | head -1 | sed 's/.*://')"
BASE="http://127.0.0.1:$PORT"
for _ in $(seq 1 30); do
  curl -fs -o /dev/null "$BASE/" && break
  sleep 1
done

# send_sensitive: one request of each sensitive kind, each printing "<status>" on its own line.
send_sensitive() {
  curl -s -m 20 -o /dev/null -w '%{http_code}\n' \
    "$BASE/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=$VERIFY_TOKEN&hub.challenge=$CHALLENGE"
  curl -s -m 20 -o /dev/null -w '%{http_code}\n' "$BASE/login/oauth2/code/dokene?code=$AUTH_CODE&state=$AUTH_STATE"
  curl -s -m 20 -o /dev/null -w '%{http_code}\n' -H "Accept: application/json" \
    -H "Referer: http://localhost:5173/customers?phone=$REFERER_TERM#$REFERER_TERM" \
    "$BASE/api/customers?phone=%2B$PHONE&name=$CUSTOMER_NAME"
  curl -s -m 20 -o /dev/null -w '%{http_code}\n' "$BASE/customers?search=$SPA_TERM"
}

# edge_output: everything the edge wrote so far, stdout (access log) and stderr (error log) together.
edge_output() { docker logs "$NAME" >"$WORK/out" 2>&1; cat "$WORK/out"; }
no_marker_in_output() { edge_output >/dev/null; ! contains_literal "$WORK/out" "${MARKERS[@]}"; }
# logged "<method> <path>" "<status>" "<upstream status>": an access log line for that request with those statuses.
logged() { edge_output >/dev/null; grep -F "\"$1 HTTP/1.1\" $2 " "$WORK/out" | grep -qF "upstream=$3"; }
# logged_matching "<method> <path>" "<status>" "<extended regex>": such a line that also matches the regex.
logged_matching() { edge_output >/dev/null; grep -F "\"$1 HTTP/1.1\" $2 " "$WORK/out" | grep -qE "$3"; }
no_error_lines() { edge_output >/dev/null; ! grep -qE '\[(error|crit|alert|emerg|warn)\]|request: "' "$WORK/out"; }

# State 1: no backend on the network, so the name does not resolve and every proxied request fails with 502.
statuses="$(send_sensitive | tr '\n' ' ')"
check "no backend: the proxied requests fail with 502 and the SPA route answers 200 (got $statuses)" \
  test "$statuses" = "502 502 502 200 "
check "no backend: no marker reaches the edge's output" no_marker_in_output
check "no backend: the webhook verification is logged by path with its status" \
  logged "GET /webhooks/whatsapp" 502 "-"
check "no backend: the OAuth callback is logged by path with its status" \
  logged "GET /login/oauth2/code/dokene" 502 "-"
check "no backend: the customer search is logged by path, and its referer without query or fragment" \
  logged_matching "GET /api/customers" 502 '"http://localhost:5173/customers" '
check "no backend: no request scoped error line is written" no_error_lines

# State 2: an over-cap webhook body, with the token in its query string, is refused with 413 at the edge.
head -c $((5 * 1024 * 1024)) /dev/zero >"$WORK/big-body"
big_status="$(curl -s -m 20 -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  --data-binary "@$WORK/big-body" "$BASE/webhooks/whatsapp?hub.verify_token=$VERIFY_TOKEN")"
check "over-cap webhook body: refused with 413 (got $big_status)" test "$big_status" = "413"
check "over-cap webhook body: no marker reaches the edge's output" no_marker_in_output
check "over-cap webhook body: logged by path with its status" logged "POST /webhooks/whatsapp" 413 "-"
check "over-cap webhook body: no request scoped error line is written" no_error_lines

# State 3: the backend name resolves, but nothing listens on its port, so the connection is refused.
docker run -d --name "$BACKEND" --network "$NET" --network-alias backend --entrypoint sleep "$IMAGE" 600 >/dev/null \
  || { echo "stand-in backend start failed" >&2; exit 1; }
sleep 11  # the edge caches name lookups for 10 seconds (resolver valid=10s)
statuses="$(send_sensitive | tr '\n' ' ')"
check "connection refused: the proxied requests fail with 502 (got $statuses)" \
  test "$statuses" = "502 502 502 200 "
check "connection refused: no marker reaches the edge's output" no_marker_in_output
check "connection refused: the access log names the upstream and its 502" \
  logged_matching "GET /webhooks/whatsapp" 502 'upstream=[0-9.]+:8080/502 '
check "connection refused: no request scoped error line is written" no_error_lines

# State 4: a stub backend answers 200, the success path.
docker rm -f "$BACKEND" >/dev/null 2>&1
docker run -d --name "$BACKEND" --network "$NET" --network-alias backend \
  -v "$WORK/stub.conf:/etc/nginx/conf.d/default.conf:ro" "$IMAGE" >/dev/null \
  || { echo "stub backend start failed" >&2; exit 1; }
sleep 11
statuses="$(send_sensitive | tr '\n' ' ')"
check "stub backend: every request answers 200 (got $statuses)" \
  test "$statuses" = "200 200 200 200 "
check "stub backend: no marker reaches the edge's output" no_marker_in_output
check "stub backend: the webhook verification is logged by path with the upstream 200" \
  logged_matching "GET /webhooks/whatsapp" 200 'upstream=[0-9.]+:8080/200 '
check "stub backend: the SPA route is logged by path only" logged "GET /customers" 200 "-"

if [ "$failures" -ne 0 ]; then
  printf '%s check(s) failed\n' "$failures" >&2
  exit 1
fi
echo "all checks passed"
