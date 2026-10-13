#!/usr/bin/env bash
# Smoke test for the frontend image's response security headers (issue #186, ADR 0023). Builds the image, runs it on a
# random local port without a backend, and checks the headers nginx sends. Needs bash, curl and a running Docker daemon;
# it never touches the Compose stack.
#
# Usage: ./scripts/tests/nginx-headers-smoke.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE="dokene-frontend:nginx-smoke"
NAME="dokene-nginx-smoke-$$"
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

headers() { curl -s -D - -o /dev/null "$BASE$1" | tr -d '\r'; }
count() { grep -ci "^$2:" <<<"$1"; }   # count "<headers>" "Header-Name"
csp_is_same_origin() { ! grep -iE "^content-security-policy:.*(unsafe-|https?:)" <<<"$1"; }
has_basic_headers() {
  grep -qi "^x-content-type-options: nosniff" <<<"$1" && grep -qi "^x-frame-options: DENY" <<<"$1" \
    && grep -qi "^referrer-policy: strict-origin-when-cross-origin" <<<"$1"
}

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

if [ "$failures" -ne 0 ]; then
  printf '%s check(s) failed\n' "$failures" >&2
  exit 1
fi
echo "all checks passed"
