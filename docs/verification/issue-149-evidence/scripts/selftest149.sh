#!/usr/bin/env bash
# ABOUTME: Offline self-test of the suite #149 problem body predicate (problem_exact): one valid INTERNAL_ERROR answer
# ABOUTME: and the negative controls a weaker check would let through. Needs only bash and jq; prints one line per case.
set -uo pipefail
_t=$(mktemp -d); export OUTD=$_t STUBDIR=$_t RESULTS=$_t/r.tsv; . "$(dirname "${BASH_SOURCE[0]}")/qa149.sh"
ok=0; bad=0
# expect <ok|reject> <what> <status> <content-type> <body>
expect() {
  CODE=$3; HDRS=$(printf 'HTTP/1.1 %s\r\nContent-Type: %s\r\n' "$3" "$4"); BODY=$5
  local r; r=$(problem_exact 500 "Internal Server Error" INTERNAL_ERROR); [ "$r" = ok ] && r=ok || r=reject
  if [ "$r" = "$1" ]; then ok=$((ok+1)); echo "ok    $2"; else bad=$((bad+1)); echo "WRONG $2 (expected $1, got $r)"; fi
}
P=application/problem+json; J=application/json
VALID='{"type":"about:blank","title":"Internal Server Error","status":500,"code":"INTERNAL_ERROR"}'
ID=00000000-0000-0000-0000-000000000123
expect ok     "the closed INTERNAL_ERROR problem" 500 $P "$VALID"
expect ok     "same, members reordered and a charset parameter" 500 "$P;charset=UTF-8" '{"code":"INTERNAL_ERROR","status":500,"title":"Internal Server Error","type":"about:blank"}'
expect reject "only a code, unknown value" 500 $P '{"code":"NOT_A_CLOSED_CODE"}'
expect reject "Spring default body plus a code (echoes the path)" 500 $P "{\"timestamp\":\"2026-10-28T00:00:00Z\",\"status\":500,\"error\":\"Internal Server Error\",\"path\":\"/api/messages/$ID/sends\",\"code\":\"INTERNAL_ERROR\"}"
expect reject "problem plus an identifier member" 500 $P "{\"type\":\"about:blank\",\"title\":\"Internal Server Error\",\"status\":500,\"code\":\"INTERNAL_ERROR\",\"messageId\":\"$ID\"}"
expect reject "problem plus a detail member" 500 $P '{"type":"about:blank","title":"Internal Server Error","status":500,"code":"INTERNAL_ERROR","detail":"x"}'
expect reject "title missing" 500 $P '{"type":"about:blank","status":500,"code":"INTERNAL_ERROR"}'
expect reject "type missing" 500 $P '{"title":"Internal Server Error","status":500,"code":"INTERNAL_ERROR"}'
expect reject "another closed code" 500 $P '{"type":"about:blank","title":"Internal Server Error","status":500,"code":"INVALID_INPUT"}'
expect reject "status member as a string" 500 $P '{"type":"about:blank","title":"Internal Server Error","status":"500","code":"INTERNAL_ERROR"}'
expect reject "status member disagrees with the HTTP status" 500 $P '{"type":"about:blank","title":"Internal Server Error","status":503,"code":"INTERNAL_ERROR"}'
expect reject "HTTP 503 with the 500 body" 503 $P "$VALID"
expect reject "HTTP 200 with the 500 body" 200 $P "$VALID"
expect reject "served as application/json" 500 $J "$VALID"
expect reject "not JSON" 500 $P '<html>Internal Server Error</html>'
expect reject "empty body" 500 $P ''
rm -rf "$_t"
echo "== selftest149.sh: ok=$ok wrong=$bad"; [ "$bad" = 0 ]
