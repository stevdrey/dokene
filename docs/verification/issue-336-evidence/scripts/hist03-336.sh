#!/usr/bin/env bash
# ABOUTME: Suite #336 HIST-03: replayed, reordered and duplicated valid callbacks: status never regresses, the inbox keeps
# ABOUTME: one row per (message, state), no duplicate applied event, attempt, provider request or phantom message.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa336.sh"
assert_safe_stack false || exit 2
qa_login testuser || exit 3
C=HIST-03; M=$M_H3; P=$(pmid "$M"); S0=$(stub_sends); N0=$(msgs_total); T=$(date +%s)
applied() { psqlq "select count(*) from dokene.outbound_message_events where message_id='$M' and event_type='DELIVERY_UPDATED' and applied"; }
step() { # label file expected-status expected-inbox-rows-for-message expected-applied
  wh_post "$2"; local r; r=$(mrow "$M")
  rec $C "$1" "$([ "$WCODE" = 200 ] && no_cookie && [[ $r == "$3/"* ]] && [ "$(psqlq "select count(*) from dokene.provider_webhook_events where provider_message_id='$P'")" = "$4" ] && [ "$(applied)" = "$5" ] && echo PASS || echo FAIL)" \
    "http=$WCODE row=$r inbox=$(inbox_for "$P") applied=$(applied)"
}
wh_body "$OUTD/h03-read.json" "$PNID_A" "$P:read:$((T + 2))"
wh_body "$OUTD/h03-read-later.json" "$PNID_A" "$P:read:$((T + 30))"
wh_body "$OUTD/h03-delivered.json" "$PNID_A" "$P:delivered:$((T + 1))"
wh_body "$OUTD/h03-mixed.json" "$PNID_A" "$P:delivered:$((T + 1))" "$P:delivered:$((T + 1))" "$P:sent:$T" "$P:read:$((T + 2))"
wh_body "$OUTD/h03-failed.json" "$PNID_A" "$P:failed:$((T + 3)):131026"
step "reordered: read arrives before delivered -> READ (SENT>READ applied)" "$OUTD/h03-read.json" READ 1 1
step "late delivered after read: acknowledged, status stays READ (recorded as not applied)" "$OUTD/h03-delivered.json" READ 2 1
E1=$(mevents "$M"); V1=$(mrow "$M")
step "byte-identical replay of the read callback: no new inbox row, no event" "$OUTD/h03-read.json" READ 2 1
step "read re-sent with another timestamp: deduplicated (one row per message and state)" "$OUTD/h03-read-later.json" READ 2 1
rec $C "replays left the message untouched (same version, same event count)" "$([ "$(mrow "$M")" = "$V1" ] && [ "$(mevents "$M")" = "$E1" ] && echo PASS || echo FAIL)" "row=$(mrow "$M") events_delta=$(( $(mevents "$M") - E1 ))"
step "one body with duplicated delivered, a stale sent and a repeated read: one new row (sent), status stays READ" "$OUTD/h03-mixed.json" READ 3 1
step "failed after read: acknowledged, no regression, no failure category" "$OUTD/h03-failed.json" READ 4 1
rec $C "H3 end state: READ, failure none, one attempt, no provider request, no new message" \
  "$([[ $(mrow "$M") == READ/unknown=false/delivered=false/read=true/failure=-/* ]] && [ "$(attempts "$M")" = 1 ] && [ "$(stub_sends)" = "$S0" ] && [ "$(msgs_total)" = "$N0" ] && [ "$(cust_msgs "$H3")" = 1 ] && echo PASS || echo FAIL)" \
  "row=$(mrow "$M") events=$(mevtypes "$M") attempts=$(attempts "$M") stub_delta=$(( $(stub_sends) - S0 )) msgs_delta=$(( $(msgs_total) - N0 ))"

# Concurrent duplicates: 6 identical delivered callbacks for H1D at once.
M=$M_H1D; P=$(pmid "$M"); E0=$(mevents "$M")
wh_body "$OUTD/h03-conc.json" "$PNID_A" "$P:delivered:$(date +%s)"
SIG=$(wh_sig "$OUTD/h03-conc.json")
for _ in 1 2 3 4 5 6; do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' -H "X-Hub-Signature-256: $SIG" --data-binary "@$OUTD/h03-conc.json" "$BFF/webhooks/whatsapp" &
done > "$OUTD/h03-conc.codes"; wait
rec $C "6 concurrent identical delivered callbacks (H1D): all acknowledged, one inbox row, one applied event, DELIVERED" \
  "$([ "$(grep -c '^200$' "$OUTD/h03-conc.codes")" = 6 ] && [ "$(psqlq "select count(*) from dokene.provider_webhook_events where provider_message_id='$P'")" = 1 ] && [ "$(mevents "$M")" = $((E0 + 1)) ] && [[ $(mrow "$M") == DELIVERED/* ]] && [ "$(attempts "$M")" = 1 ] && [ "$(stub_sends)" = "$S0" ] && echo PASS || echo FAIL)" \
  "codes=$(sort "$OUTD/h03-conc.codes" | uniq -c | awk '{printf "%sx%s ", $1, $2}')inbox=$(inbox_for "$P") row=$(mrow "$M") events_delta=$(( $(mevents "$M") - E0 ))"
qa_finish
