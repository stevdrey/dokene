#!/usr/bin/env bash
# ABOUTME: Suite #335 window W1, SEND-05 API part: Idempotency-Key replay, conflicting reuse, body payload, and
# ABOUTME: concurrent same-message sends (distinct keys and one shared key). Exactly one logical send per message.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
# Session values (the CSRF header below) reach curl only through the protected wrapper (issue 327, invariant 35).
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)/scripts/lib/curl-protected.sh" || { echo "scripts/lib/curl-protected.sh not found" >&2; exit 1; }
. "$(dirname "${BASH_SOURCE[0]}")/qa335.sh"
assert_safe_stack true || exit 2
stub_reset
qa_login testuser || exit 3
C=SEND-05

# Replay and conflicting reuse on M_S5KEY.
S0=$(stub_sends)
req testuser "$A" GET "/api/messages/$M_S5KEY" -; V0=$(etag); K=$(uuidgen)
req testuser "$A" POST "/api/messages/$M_S5KEY/sends" - -H "If-Match: $V0" -H "Idempotency-Key: $K"
H1=$CODE; P1=$(jb .providerMessageId); ST=$(jb .status)
rec $C "first send with key K: 201 SENT, 1 attempt, 1 provider request" "$([ "$H1" = 201 ] && [ "$ST" = SENT ] && [ "$(attempts "$M_S5KEY")" = 1 ] && [ "$(stub_sends)" = $((S0 + 1)) ] && echo PASS || echo FAIL)" "http=$H1 status=$ST attempts=$(attempts "$M_S5KEY") stub_delta=$(( $(stub_sends) - S0 ))"
req testuser "$A" POST "/api/messages/$M_S5KEY/sends" - -H "If-Match: $V0" -H "Idempotency-Key: $K"
rec $C "same key, same If-Match replayed: 200, same provider id, no new attempt or provider request" "$([ "$CODE" = 200 ] && [ "$(jb .providerMessageId)" = "$P1" ] && [ "$(attempts "$M_S5KEY")" = 1 ] && [ "$(stub_sends)" = $((S0 + 1)) ] && echo PASS || echo FAIL)" "http=$CODE status=$(jb .status) attempts=$(attempts "$M_S5KEY") stub_delta=$(( $(stub_sends) - S0 ))"
req testuser "$A" GET "/api/messages/$M_S5KEY" -; V1=$(etag)
req testuser "$A" POST "/api/messages/$M_S5KEY/sends" - -H "If-Match: $V1" -H "Idempotency-Key: $K"
rec $C "same key with an altered request (current If-Match) rejected IDEMPOTENCY_KEY_REUSED" "$([ "$(code)" = IDEMPOTENCY_KEY_REUSED ] && [ "$(stub_sends)" = $((S0 + 1)) ] && echo PASS || echo FAIL)" "http=$CODE code=$(code)"
req testuser "$A" GET "/api/messages/$M_S5CONC" -
req testuser "$A" POST "/api/messages/$M_S5CONC/sends" - -H "If-Match: $(etag)" -H "Idempotency-Key: $K"
H=$CODE; G=$(code)
rec $C "same key on another APPROVED message rejected IDEMPOTENCY_KEY_REUSED, that message untouched" "$([ "$G" = IDEMPOTENCY_KEY_REUSED ] && [ "$(attempts "$M_S5CONC")" = 0 ] && [ "$(stub_sends)" = $((S0 + 1)) ] && echo PASS || echo FAIL)" "http=$H code=$G attempts=$(attempts "$M_S5CONC") state=$(mstate "$M_S5CONC")"
req testuser "$A" GET "/api/messages/$M_S5CONC" -
req testuser "$A" POST "/api/messages/$M_S5CONC/sends" '{"recipientPhone":"+56900000000"}' -H "If-Match: $(etag)" -H "Idempotency-Key: $(uuidgen)"
rec $C "send with a body payload rejected 400 INVALID_INPUT before anything happens" "$([ "$CODE" = 400 ] && [ "$(code)" = INVALID_INPUT ] && [ "$(attempts "$M_S5CONC")" = 0 ] && echo PASS || echo FAIL)" "http=$CODE code=$(code)"
send testuser "$M_S5KEY"
rec $C "new key on the already SENT message refused INVALID_TRANSITION, no provider request" "$([ "$(code)" = INVALID_TRANSITION ] && [ "$(attempts "$M_S5KEY")" = 1 ] && [ "$(stub_sends)" = $((S0 + 1)) ] && echo PASS || echo FAIL)" "http=$CODE code=$(code)"

# psend <message> <n> <same|distinct>: n simultaneous sends at the same version; prints the sorted HTTP codes.
psend() {
  local m=$1 n=$2 mode=$3 tok v k i d
  tok=$(csrf testuser); req testuser "$A" GET "/api/messages/$m" -; v=$(etag); k=$(uuidgen); d=$(mktemp -d "$QA_TMP/par.XXXX")
  for i in $(seq 1 "$n"); do
    [ "$mode" = distinct ] && k=$(uuidgen)
    curl -s -o "$d/b$i" -w '%{http_code}\n' -X POST -b "$QA_TMP/jar-testuser.txt" -H "X-CSRF-TOKEN: $tok" -H "X-Tenant-Id: $A" \
      -H "If-Match: $v" -H "Idempotency-Key: $k" "$BFF/api/messages/$m/sends" > "$d/c$i" &
  done
  wait
  PCODES=$(cat "$d"/c* | sort | uniq -c | awk '{printf "%sx%s ", $1, $2}')
  PERR=$(cat "$d"/b* | jq -r '.code // empty' 2>/dev/null | sort | uniq -c | awk '{printf "%sx%s ", $1, $2}')
  N201=$(cat "$d"/c* | grep -c 201); rm -rf "$d"
}
# A 1.5 s provider delay keeps the first claim's call open while the others arrive.
stub_delay 1500
S0=$(stub_sends); psend "$M_S5CONC" 8 distinct
rec $C "8 concurrent sends, distinct keys, same version: exactly one 201, one attempt, one provider request" "$([ "$N201" = 1 ] && [ "$(attempts "$M_S5CONC")" = 1 ] && [ "$(stub_sends)" = $((S0 + 1)) ] && echo PASS || echo FAIL)" "http=${PCODES% } refusals=${PERR% } status=$(mstate "$M_S5CONC") stub_delta=$(( $(stub_sends) - S0 ))"
mkcust S5SAME "QA335 Bruno MismaClave" && mkmsg M_S5SAME testuser "$S5SAME" "$S5SAME_C" approve
S0=$(stub_sends); psend "$M_S5SAME" 8 same
rec $C "8 concurrent sends sharing one key: one 201, the rest replays or refusals, one attempt, one provider request" "$([ "$N201" = 1 ] && [ "$(attempts "$M_S5SAME")" = 1 ] && [ "$(stub_sends)" = $((S0 + 1)) ] && echo PASS || echo FAIL)" "http=${PCODES% } refusals=${PERR% } status=$(mstate "$M_S5SAME") stub_delta=$(( $(stub_sends) - S0 ))"
stub_delay 0
for m in "$M_S5KEY" "$M_S5CONC" "$M_S5SAME"; do rec $C "append-only trail ${m:0:8}" NOTE "$(events "$m")"; done
qa_finish
