#!/usr/bin/env bash
# ABOUTME: Suite #336 HIST-02: one signed synthetic Meta callback over real HTTP advances the same message (delivered,
# ABOUTME: then read) with no extra send, attempt or message; the browser history is refreshed between the steps.
#   hist02-336.sh delivered | read
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa336.sh"
assert_safe_stack false || exit 2
qa_login testuser || exit 3
C=HIST-02; M=$M_H2; ST=${1:?delivered|read}
P=$(pmid "$M"); S0=$(stub_sends); E0=$(mevents "$M"); N0=$(msgs_total); R0=$(mrow "$M")
F=$OUTD/h02-$ST.json; wh_body "$F" "$PNID_A" "$P:$ST:$(date +%s)"
wh_post "$F"
want=$([ "$ST" = delivered ] && echo DELIVERED || echo READ)
rec $C "signed $ST callback over real HTTP: 200, no Set-Cookie" "$([ "$WCODE" = 200 ] && no_cookie && echo PASS || echo FAIL)" "http=$WCODE"
R1=$(mrow "$M")
rec $C "$ST advances the same message to $want; one applied event; attempts, messages and provider sends unchanged" \
  "$([[ $R1 == "$want/"* ]] && [ "$(mevents "$M")" = $((E0 + 1)) ] && [ "$(attempts "$M")" = 1 ] && [ "$(msgs_total)" = "$N0" ] && [ "$(stub_sends)" = "$S0" ] && echo PASS || echo FAIL)" \
  "before=$R0 after=$R1 events=$(mevtypes "$M") inbox=$(inbox_for "$P") msgs_delta=$(( $(msgs_total) - N0 )) stub_delta=$(( $(stub_sends) - S0 ))"
req testuser "$A" GET "/api/messages/$M" -
rec $C "API detail agrees: status $want, deliveredAt/readAt set as reported, attempts 1 ACCEPTED" \
  "$([ "$(jb .status)" = "$want" ] && [ "$(jb '.deliveredAt != null')" = true ] && { [ "$ST" = delivered ] || [ "$(jb '.readAt != null')" = true ]; } && [ "$(jb '[.attempts[].outcome]|join(",")')" = ACCEPTED ] && echo PASS || echo FAIL)" \
  "status=$(jb .status) delivered=$(jb '.deliveredAt != null') read=$(jb '.readAt != null') attempts=$(jb '[.attempts[].outcome]|join(",")')"
req testuser "$A" GET "/api/messages?customerId=$H2" -
rec $C "customer history (list API) shows one row, status $want" "$([ "$(jb '.items|length')" = 1 ] && [ "$(jb '.items[0].status')" = "$want" ] && echo PASS || echo FAIL)" "rows=$(jb '.items|length') status=$(jb '.items[0].status')"
qa_finish
