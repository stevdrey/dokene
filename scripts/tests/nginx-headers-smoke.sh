#!/usr/bin/env bash
# ABOUTME: Smoke test for the frontend image: the response security headers it sends and the request headers it forwards.
# ABOUTME: Runs the edge in front of an echo upstream from the same image and checks what reaches it (issues #186, #320).
#
# Smoke test for the frontend image's response security headers (issue #186, ADR 0023), and for the forwarding header
# contract between the edge and the backend (issue #320, spec 0018). Builds the image and runs it on a random local
# port in front of a second container from the same image, whose stub config answers every request with one
# `name=[value]` line per header of interest; `name=[]` means the edge did not send that header. Both sit on a test
# network, the stub with the alias `backend`, because the edge's resolver needs Docker's network DNS. Requests come from
# curl on the host. Needs bash, curl and a running Docker daemon; it never touches the Compose stack.
#
# Usage: ./scripts/tests/nginx-headers-smoke.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE="dokene-frontend:nginx-smoke"
NAME="dokene-nginx-smoke-$$"
ECHO="dokene-nginx-smoke-echo-$$"
NET="dokene-nginx-smoke-$$"
NGINX_CONF="$ROOT_DIR/infra/docker/frontend/nginx.conf"
WORK="$(mktemp -d)"
failures=0

# The headers the edge must never send upstream (AC-1), then the ones it sets itself.
STRIPPED=(forwarded x-forwarded-host x-forwarded-prefix x-forwarded-ssl x-forwarded-server
  x-forwarded-scheme x-original-forwarded-for x-real-ip x-client-ip true-client-ip)
ECHOED=("${STRIPPED[@]}" x-forwarded-for x-forwarded-proto x-forwarded-port host)

# A forged value in every header of interest (documentation addresses and names only).
FORGED=(-H 'Forwarded: for=192.0.2.66;host=evil.example;proto=https' -H 'X-Forwarded-Host: evil.example'
  -H 'X-Forwarded-Port: 443' -H 'X-Forwarded-Prefix: /evil' -H 'X-Forwarded-Ssl: on'
  -H 'X-Forwarded-Server: evil.example' -H 'X-Forwarded-Scheme: https' -H 'X-Original-Forwarded-For: 192.0.2.66'
  -H 'X-Real-IP: 192.0.2.66' -H 'X-Client-IP: 192.0.2.66' -H 'True-Client-IP: 192.0.2.66'
  -H 'X-Forwarded-For: 192.0.2.66' -H 'X-Forwarded-Proto: https' -H 'Host: evil.example')
# The same names sent twice, in mixed letter case.
REPEATED=(-H 'forwarded: for=192.0.2.66' -H 'FORWARDED: host=evil.example'
  -H 'x-forwarded-host: evil.example' -H 'X-FORWARDED-HOST: evil2.example'
  -H 'x-real-ip: 192.0.2.66' -H 'X-REAL-IP: 192.0.2.67'
  -H 'x-forwarded-for: 192.0.2.66' -H 'X-FORWARDED-FOR: 192.0.2.67'
  -H 'x-FORWARDED-proto: https' -H 'X-Forwarded-PROTO: https')

pass() { printf '[pass] %s\n' "$1"; }
fail() { printf '[FAIL] %s\n' "$1" >&2; failures=$((failures + 1)); }
check() {
  # check "description" command...
  local description="$1"; shift
  if "$@" >/dev/null 2>&1; then pass "$description"; else fail "$description"; fi
}

cleanup() {
  docker rm -f "$NAME" "$ECHO" >/dev/null 2>&1
  docker network rm "$NET" >/dev/null 2>&1
  docker rmi "$IMAGE" >/dev/null 2>&1
  rm -rf "$WORK"
}
trap cleanup EXIT

# Static check, no Docker. nginx inherits proxy_set_header from an outer level only when a block sets none of its own,
# so every upstream header line lives in proxy-upstream.conf and every proxy_pass location includes it.
# proxied_locations prints "<proxy_pass locations> <of them without the snippet>".
proxied_locations() {
  awk '
    /^[[:space:]]*#/ { next }
    /^[[:space:]]*location[[:space:]]/ { inside = 1; depth = 0; passes = 0; includes = 0 }
    inside {
      depth += gsub(/\{/, "{") - gsub(/\}/, "}")
      if ($0 ~ /^[[:space:]]*proxy_pass[[:space:]]/) passes = 1
      if ($0 ~ /^[[:space:]]*include[[:space:]]+\/etc\/nginx\/snippets\/proxy-upstream\.conf;/) includes = 1
      if (depth == 0) { if (passes) { total++; if (!includes) missing++ } inside = 0 }
    }
    END { print total + 0, missing + 0 }
  ' "$NGINX_CONF"
}
check "nginx.conf sets no proxy_set_header of its own" \
  test "$(grep -cE '^[[:space:]]*proxy_set_header' "$NGINX_CONF")" -eq 0
check "both proxy_pass locations include proxy-upstream.conf (got: $(proxied_locations))" \
  test "$(proxied_locations)" = "2 0"
check "the shipped trusted-proxies.conf lists no proxy" \
  test "$(grep -cE '^[[:space:]]*set_real_ip_from' "$ROOT_DIR/infra/docker/frontend/trusted-proxies.conf")" -eq 0

# The echo upstream: one "name=[$http_name]" line per header of interest. nginx joins repeated headers with commas.
# /webhooks/body-check records the raw request body instead: nginx fills $request_body only when it reads the body
# for a proxy_pass, so that route proxies to a second local server and logs the body unescaped to a file.
# shellcheck disable=SC2016  # $request_body and $http_* are nginx variables, written literally into the stub config
{
  printf 'log_format webhook_body escape=none "$request_body";\n'
  printf 'server {\n    listen 8080;\n'
  printf '    location = /webhooks/body-check {\n        access_log /tmp/webhook-body.log webhook_body;\n'
  printf '        proxy_pass http://127.0.0.1:8081;\n    }\n'
  printf '    location / {\n        default_type text/plain;\n        return 200 "'
  for h in "${ECHOED[@]}"; do printf '%s=[$http_%s]\\n' "$h" "${h//-/_}"; done
  printf '";\n    }\n}\n'
  printf 'server {\n    listen 127.0.0.1:8081;\n    location / {\n        return 200 "received\\n";\n    }\n}\n'
} >"$WORK/echo.conf"
chmod 644 "$WORK/echo.conf"

docker build -q --build-context "nginx=$ROOT_DIR/infra/docker/frontend" \
  -f "$ROOT_DIR/infra/docker/frontend/Dockerfile" -t "$IMAGE" "$ROOT_DIR/frontend" >/dev/null \
  || { echo "image build failed" >&2; exit 1; }
docker network create "$NET" >/dev/null || { echo "network create failed" >&2; exit 1; }
docker run -d --name "$ECHO" --network "$NET" --network-alias backend \
  -v "$WORK/echo.conf:/etc/nginx/conf.d/default.conf:ro" "$IMAGE" >/dev/null \
  || { echo "echo container start failed" >&2; exit 1; }

# start_edge [docker run options...]: (re)starts the edge and waits until it reaches the echo upstream.
start_edge() {
  docker rm -f "$NAME" >/dev/null 2>&1
  docker run -d --name "$NAME" --network "$NET" -p 127.0.0.1::8080 "$@" "$IMAGE" >/dev/null \
    || { echo "edge container start failed" >&2; exit 1; }
  PORT="$(docker port "$NAME" 8080/tcp | head -1 | sed 's/.*://')"
  BASE="http://127.0.0.1:$PORT"
  for _ in $(seq 1 30); do
    curl -fs "$BASE/api/ready" 2>/dev/null | grep -q '^host=' && return 0
    sleep 1
  done
  echo "edge did not reach the echo upstream" >&2
  exit 1
}

headers() { curl -s -D - -o /dev/null "$BASE$1" | tr -d '\r'; }
count() { grep -ci "^$2:" <<<"$1"; }   # count "<headers>" "Header-Name"
csp_is_same_origin() { ! grep -iE "^content-security-policy:.*(unsafe-|https?:)" <<<"$1"; }
has_basic_headers() {
  grep -qi "^x-content-type-options: nosniff" <<<"$1" && grep -qi "^x-frame-options: DENY" <<<"$1" \
    && grep -qi "^referrer-policy: strict-origin-when-cross-origin" <<<"$1"
}

# upstream "<path>" [curl options...]: what the echo upstream received for that request through the edge.
upstream() { local path="$1"; shift; curl -s "$@" "$BASE$path"; }
# value "<upstream output>" "<header>": the value the upstream received for one header, empty when not sent.
value() { sed -n "s/^$2=\[\(.*\)\]\$/\1/p" <<<"$1"; }
none_stripped() {
  local h
  for h in "${STRIPPED[@]}"; do grep -qx "$h=\[\]" <<<"$1" || return 1; done
}
is() { test "$(value "$1" "$2")" = "$3"; }   # is "<upstream output>" "<header>" "<expected value>"

start_edge

asset="$(curl -s "$BASE/" | grep -o '/assets/index-[^"]*\.js' | head -1)"
[ -n "$asset" ] || fail "index.html references a hashed JS asset"

for path in / "$asset" /fonts/inter-variable.woff2 /some/spa/route; do
  h="$(headers "$path")"
  check "$path sends exactly one CSP" test "$(count "$h" Content-Security-Policy)" -eq 1
  check "$path sends exactly one Permissions-Policy" test "$(count "$h" Permissions-Policy)" -eq 1
  check "$path CSP is same-origin only (no unsafe-*, no third-party host)" csp_is_same_origin "$h"
  check "$path CSP forbids framing" grep -qi "frame-ancestors 'none'" <<<"$h"
  check "$path sends nosniff, frame denial and referrer policy" has_basic_headers "$h"
done

check "fonts are cached for 30 days" grep -qi "^cache-control: public, max-age=2592000" <<<"$(headers /fonts/inter-variable.woff2)"
check "the entry HTML is always revalidated" grep -qi "^cache-control: no-cache" <<<"$(headers /)"
# Proxied BFF routes: the backend owns those headers (TenantSecurityConfiguration); nginx must not add its own copy.
check "proxied /api/ routes get no nginx-added CSP" test "$(count "$(headers /api/session)" Content-Security-Policy)" -eq 0

# The peer address the edge sees for curl on this host. Linux CI and Docker Desktop route it from different gateways.
PEER="$(value "$(upstream /api/probe)" x-forwarded-for)"
check "the edge reports a single peer address for this host (got '$PEER')" grep -qE '^[0-9a-fA-F.:]+$' <<<"$PEER"

# untrusted_case "<label>" "<path>" [curl options...]: the AC-1 and AC-2 checks for one request from an untrusted peer.
untrusted_case() {
  local label="$1" path="$2" out
  shift 2
  out="$(upstream "$path" "$@")"
  check "untrusted $label: no stripped header reaches the backend" none_stripped "$out"
  check "untrusted $label: X-Forwarded-For is the peer only" is "$out" x-forwarded-for "$PEER"
  check "untrusted $label: X-Forwarded-Proto is the edge's http" is "$out" x-forwarded-proto http
  # Spring clears the port when it sees X-Forwarded-Proto, so the edge pins the port of its fixed Host (spec 0018).
  check "untrusted $label: X-Forwarded-Port is the pinned 8080" is "$out" x-forwarded-port 8080
  check "untrusted $label: Host stays localhost:8080" is "$out" host localhost:8080
}

# Untrusted peer (AC-1, AC-2): no forwarding header the client sent reaches the backend.
untrusted_case "/api, forged headers" /api/session "${FORGED[@]}"
untrusted_case "/api, repeated mixed case headers" /api/session "${REPEATED[@]}"
untrusted_case "/oauth2, forged headers" /oauth2/authorization/dokene "${FORGED[@]}"
untrusted_case "/oauth2, repeated mixed case headers" /oauth2/authorization/dokene "${REPEATED[@]}"
untrusted_case "POST /webhooks, forged headers" /webhooks/whatsapp -X POST -H 'Content-Type: application/json' \
  --data '{}' "${FORGED[@]}"
untrusted_case "POST /webhooks, repeated mixed case headers" /webhooks/whatsapp -X POST --data '{}' "${REPEATED[@]}"

# The webhook body reaches the backend byte for byte (AC-5, AC-6): the backend's HMAC is computed over those bytes.
WEBHOOK_BODY="$(printf '{"object":"whatsapp_business_account","entry":[{"text":"Señor €5 \\"ok\\" a\\\\b\tend"}]}')"
body_status="$(printf '%s' "$WEBHOOK_BODY" | curl -s -o /dev/null -w '%{http_code}' -X POST \
  -H 'Content-Type: application/json' "${FORGED[@]}" --data-binary @- "$BASE/webhooks/body-check")"
check "POST /webhooks body check reached the echo upstream (got $body_status)" test "$body_status" = 200
received_body="$(docker exec "$ECHO" cat /tmp/webhook-body.log 2>/dev/null)"
check "POST /webhooks: the body arrives byte for byte" test "$received_body" = "$WEBHOOK_BODY"

# The shipped trusted-proxies.conf (AC-4): the image lists no proxy, so the trusted case below is treated as untrusted.
check "the image's trusted-proxies.conf lists no proxy" test "$(docker exec "$NAME" \
  grep -cE '^[[:space:]]*set_real_ip_from' /etc/nginx/snippets/trusted-proxies.conf)" -eq 0
out="$(upstream /api/session -H 'X-Forwarded-For: 203.0.113.7')"
check "shipped file: a client address from this peer is ignored" is "$out" x-forwarded-for "$PEER"

# Trusted peer (AC-3): mount a list naming this host's peer and one documentation range, then restart the edge.
printf 'set_real_ip_from %s;\nset_real_ip_from 198.51.100.0/24;\n' "$PEER" >"$WORK/trusted-proxies.conf"
chmod 644 "$WORK/trusted-proxies.conf"
start_edge -v "$WORK/trusted-proxies.conf:/etc/nginx/snippets/trusted-proxies.conf:ro"

out="$(upstream /api/session -H 'X-Forwarded-For: 203.0.113.7')"
check "trusted peer: its client address is restored" is "$out" x-forwarded-for 203.0.113.7
out="$(upstream /api/session -H 'X-Forwarded-For: 192.0.2.66, 203.0.113.7')"
check "trusted peer: the rightmost untrusted address is used, not a forged one left of it" \
  is "$out" x-forwarded-for 203.0.113.7
out="$(upstream /api/session -H 'X-Forwarded-For: 203.0.113.7' -H 'X-Forwarded-Proto: https')"
check "trusted peer: its https is not believed, X-Forwarded-Proto stays http" is "$out" x-forwarded-proto http
out="$(upstream /api/session -H 'X-Forwarded-For: 203.0.113.7, not-an-address')"
check "trusted peer: a malformed entry leaves the peer address in place" is "$out" x-forwarded-for "$PEER"
out="$(upstream /api/session -H 'X-Forwarded-For: 203.0.113.7:4321')"
check "trusted peer: an address with a port is restored without the port (got '$(value "$out" x-forwarded-for)')" \
  is "$out" x-forwarded-for 203.0.113.7
out="$(upstream /api/session -H 'X-Forwarded-For: 198.51.100.9, 198.51.100.10')"
check "trusted peer: when the whole chain is trusted, the leftmost address is used" \
  is "$out" x-forwarded-for 198.51.100.9
out="$(upstream /api/session)"
check "trusted peer without X-Forwarded-For: the peer address is used" is "$out" x-forwarded-for "$PEER"
out="$(upstream /oauth2/authorization/dokene "${FORGED[@]}")"
check "trusted peer, forged headers: no stripped header reaches the backend" none_stripped "$out"
check "trusted peer, forged headers: X-Forwarded-For is the restored client only" \
  is "$out" x-forwarded-for 192.0.2.66
check "trusted peer, forged headers: X-Forwarded-Proto stays http" is "$out" x-forwarded-proto http
check "trusted peer, forged headers: X-Forwarded-Port stays 8080" is "$out" x-forwarded-port 8080
check "trusted peer, forged headers: Host stays localhost:8080" is "$out" host localhost:8080

if [ "$failures" -ne 0 ]; then
  printf '%s check(s) failed\n' "$failures" >&2
  exit 1
fi
echo "all checks passed"
