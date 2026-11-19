#!/usr/bin/env bash
# ABOUTME: Smoke test for the frontend image's logs (issue #330): no query string, credential or PII reaches them,
# ABOUTME: on success and on failure paths, while every request still leaves one sanitized access log line.
#
# Sends requests whose query strings and Referer carry synthetic values of the sensitive kinds the edge sees: the Meta
# webhook verify token and challenge (also after an encoded `?`), an OAuth callback code and state, customer search terms
# (a phone number and a name), a session id in a `;jsessionid=` path parameter of the request and of its Referer, and
# userinfo in a Referer. Every value is a fresh random marker made by this run; none is a real credential. The requests
# go through the edge in four states: no backend at all (the name does not resolve), a backend name with nothing
# listening (connection refused), a webhook body over the 4 MiB cap (413), and a stub backend that answers 200. After
# each state the edge's
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
# nginx.conf and every snippet the image copies next to it: a log directive in any of them reaches the edge.
CONF_FILES=("$ROOT_DIR"/infra/docker/frontend/*.conf)
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
REFERER_SID="$(marker rsid)"
SPA_TERM="$(marker spa)"
SESSION_ID="$(marker sid)"
ENCODED_TOKEN="$(marker enc)"
USERINFO="$(marker ui)"
MARKERS=("$VERIFY_TOKEN" "$CHALLENGE" "$AUTH_CODE" "$AUTH_STATE" "$PHONE" "$CUSTOMER_NAME" "$REFERER_TERM" "$REFERER_SID" "$SPA_TERM"
  "$SESSION_ID" "$ENCODED_TOKEN" "$USERINFO")

# Static checks, no Docker, over nginx.conf and its snippets. The base image's nginx.conf logs "$request" (the request
# line, query string included) at http level. An access_log at that same level would add a second log rather than
# replace it, so the redacted one is set inside the server block, and it is the only one.
directives() { cat "${CONF_FILES[@]}" | grep -cE "^[[:space:]]*$1"; }
check "the edge config sets exactly one access_log, with the redacted format" \
  test "$(directives 'access_log[[:space:]]')" -eq 1 -a \
  "$(directives 'access_log[[:space:]]+/dev/stdout[[:space:]]+redacted;')" -eq 1
# The error log is silenced once, for the whole server, and nowhere else: a location level error_log would bring the
# request lines back for that location, and a static route fails with the query string in its line too.
check "the edge config sets exactly one error_log, to /dev/null, in the server block" \
  test "$(directives 'error_log[[:space:]]')" -eq 1 -a \
  "$(grep -cE '^    error_log[[:space:]]+/dev/null;' "$NGINX_CONF")" -eq 1
# Allowlist: the only variables the redacted format may name. Anything else (a header, a cookie, an argument, the raw
# request line or URI) fails, whatever its name.
ALLOWED_VARIABLES=" remote_addr time_local request_method request_path server_protocol status body_bytes_sent request_time
 upstream_addr upstream_status upstream_response_time referer_path "
format_variables() { sed -n '/^log_format redacted/,/;/p' "$NGINX_CONF" | grep -oE '[$][A-Za-z0-9_]+' | tr -d '$'; }
format_is_safe() {
  local variables variable
  variables="$(format_variables)"
  [ -n "$variables" ] || return 1
  for variable in $variables; do
    case "$ALLOWED_VARIABLES" in *" $variable "* | *" $variable"$'\n'*) ;; *) return 1 ;; esac
  done
}
check "the redacted format names only allowlisted variables (got: $(format_variables | tr '\n' ' '))" format_is_safe

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
    -H "Referer: http://localhost:5173/customers;jsessionid=$REFERER_SID?phone=$REFERER_TERM#$REFERER_TERM" \
    "$BASE/api/customers?phone=%2B$PHONE&name=$CUSTOMER_NAME"
  curl -s -m 20 -o /dev/null -w '%{http_code}\n' -H "Referer: http://qa:$USERINFO@localhost:5173/x%3Bjsessionid=$REFERER_SID?y=1" \
    "$BASE/api/session;jsessionid=$SESSION_ID"
  curl -s -m 20 -o /dev/null -w '%{http_code}\n' "$BASE/webhooks/whatsapp%3Fhub.verify_token=$ENCODED_TOKEN"
  curl -s -m 20 -o /dev/null -w '%{http_code}\n' "$BASE/customers?search=$SPA_TERM"
}

# edge_output: everything the edge wrote so far, stdout (access log) and stderr (error log) together.
edge_output() { docker logs "$NAME" >"$WORK/out" 2>&1; cat "$WORK/out"; }
no_marker_in_output() { edge_output >/dev/null; ! contains_literal "$WORK/out" "${MARKERS[@]}"; }
# logged "<method> <path>" "<status>" "<upstream status>": an access log line for that request with those statuses.
logged() { edge_output >/dev/null; grep -F "\"$1 HTTP/1.1\" $2 " "$WORK/out" | grep -qF " upstream=$3 "; }
# logged_matching "<method> <path>" "<status>" "<extended regex>": such a line that also matches the regex.
logged_matching() { edge_output >/dev/null; grep -F "\"$1 HTTP/1.1\" $2 " "$WORK/out" | grep -qE "$3"; }
# wait_until <command...>: retries the command once a second for up to 30 seconds; false when it never succeeds.
wait_until() {
  local _
  for _ in $(seq 1 30); do
    "$@" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}
# probe_reaches "<extended regex>": sends a request without sensitive data and checks its newest access log line.
probe_reaches() {
  curl -s -m 5 -o /dev/null "$BASE/api/ready"
  edge_output >/dev/null
  grep -F '"GET /api/ready HTTP/1.1"' "$WORK/out" | tail -n 1 | grep -qE "$1"
}
no_error_lines() { edge_output >/dev/null; ! grep -qE '\[(error|crit|alert|emerg|warn)\]|request: "' "$WORK/out"; }

# State 1: no backend on the network, so the name does not resolve and every proxied request fails with 502.
statuses="$(send_sensitive | tr '\n' ' ')"
check "no backend: the proxied requests fail with 502 and the SPA route answers 200 (got $statuses)" \
  test "$statuses" = "502 502 502 502 502 200 "
check "no backend: no marker reaches the edge's output" no_marker_in_output
check "no backend: the webhook verification is logged by path with its status" \
  logged "GET /webhooks/whatsapp" 502 "-/-"
check "no backend: the OAuth callback is logged by path with its status" \
  logged "GET /login/oauth2/code/dokene" 502 "-/-"
check "no backend: the customer search is logged by path, and its Referer without path parameter, query or fragment" \
  logged_matching "GET /api/customers" 502 '"http://localhost:5173/customers"$'
check "no backend: a ;jsessionid= path parameter is cut from the logged path" logged "GET /api/session" 502 "-/-"
check "no backend: a Referer is logged without its userinfo, encoded path parameter or query" \
  logged_matching "GET /api/session" 502 '"http://localhost:5173/x"$'
check "no backend: a request whose path holds an encoded ? is logged up to it" \
  test "$(grep -cF '"GET /webhooks/whatsapp HTTP/1.1" 502 ' "$WORK/out")" -eq 2
check "no backend: no request scoped error line is written" no_error_lines

# State 2: an over-cap webhook body, with the token in its query string, is refused with 413 at the edge.
head -c $((5 * 1024 * 1024)) /dev/zero >"$WORK/big-body"
big_status="$(curl -s -m 20 -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  --data-binary "@$WORK/big-body" "$BASE/webhooks/whatsapp?hub.verify_token=$VERIFY_TOKEN")"
check "over-cap webhook body: refused with 413 (got $big_status)" test "$big_status" = "413"
check "over-cap webhook body: no marker reaches the edge's output" no_marker_in_output
check "over-cap webhook body: logged by path with its status" logged "POST /webhooks/whatsapp" 413 "-/-"
check "over-cap webhook body: no request scoped error line is written" no_error_lines
# A static route fails with its query string in the error line as well (issue 330 review): a body over the default cap.
static_status="$(curl -s -m 20 -o /dev/null -w '%{http_code}' -X POST --data-binary "@$WORK/big-body" \
  "$BASE/customers?search=$SPA_TERM")"
check "over-cap body on a static route: refused with 413 (got $static_status)" test "$static_status" = "413"
check "over-cap body on a static route: no marker reaches the edge's output" no_marker_in_output
check "over-cap body on a static route: no request scoped error line is written" no_error_lines

# State 3: the backend name resolves, but nothing listens on its port, so the connection is refused.
docker run -d --name "$BACKEND" --network "$NET" --network-alias backend --entrypoint sleep "$IMAGE" 600 >/dev/null \
  || { echo "stand-in backend start failed" >&2; exit 1; }
# The edge caches name lookups, failed ones too, for 10 seconds (resolver valid=10s).
wait_until probe_reaches 'upstream=[0-9.]+:8080/502 ' || { echo "the edge never reached the stand-in backend" >&2; exit 1; }
statuses="$(send_sensitive | tr '\n' ' ')"
check "connection refused: the proxied requests fail with 502 (got $statuses)" \
  test "$statuses" = "502 502 502 502 502 200 "
check "connection refused: no marker reaches the edge's output" no_marker_in_output
check "connection refused: the access log names the upstream and its 502" \
  logged_matching "GET /webhooks/whatsapp" 502 'upstream=[0-9.]+:8080/502 '
check "connection refused: no request scoped error line is written" no_error_lines

# State 4: a stub backend answers 200, the success path.
docker rm -f "$BACKEND" >/dev/null 2>&1
docker run -d --name "$BACKEND" --network "$NET" --network-alias backend \
  -v "$WORK/stub.conf:/etc/nginx/conf.d/default.conf:ro" "$IMAGE" >/dev/null \
  || { echo "stub backend start failed" >&2; exit 1; }
wait_until probe_reaches '" 200 [0-9]+ .* upstream=[0-9.]+:8080/200 ' \
  || { echo "the edge never reached the stub backend" >&2; exit 1; }
statuses="$(send_sensitive | tr '\n' ' ')"
check "stub backend: every request answers 200 (got $statuses)" \
  test "$statuses" = "200 200 200 200 200 200 "
check "stub backend: no marker reaches the edge's output" no_marker_in_output
check "stub backend: the webhook verification is logged by path with the upstream 200" \
  logged_matching "GET /webhooks/whatsapp" 200 'upstream=[0-9.]+:8080/200 '
check "stub backend: the SPA route is logged by path only" logged "GET /customers" 200 "-/-"

if [ "$failures" -ne 0 ]; then
  printf '%s check(s) failed\n' "$failures" >&2
  exit 1
fi
echo "all checks passed"
