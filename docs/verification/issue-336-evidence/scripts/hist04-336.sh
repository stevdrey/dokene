#!/usr/bin/env bash
# ABOUTME: Suite #336 HIST-04: forged, missing and malformed signatures, tampered and malformed bodies, unknown numbers,
# ABOUTME: a wrong provider reference and a cross-tenant reference: no unauthorized change, no session, no Set-Cookie.
#   hist04-336.sh           (immediate checks)   |   hist04-336.sh late   (after the 15 min park window)
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa336.sh"
assert_safe_stack false || exit 2
qa_login testuser || exit 3
C=HIST-04; M=$M_H4; P=$(pmid "$M")
if [ "${1:-}" = late ]; then
  rec $C "after the park window: the wrong-reference and cross-tenant reports are UNMATCHED with the provider id cleared" \
    "$([ "$(psqlq "select count(*) from dokene.provider_webhook_events where id in ($(paste -sd, "$OUTD/h04-parked.ids")) and state='UNMATCHED' and provider_message_id is null")" = "$(wc -l < "$OUTD/h04-parked.ids")" ] && echo PASS || echo FAIL)" \
    "rows=$(psqlq "select string_agg(case when tenant_id='$A' then 'A' else 'B' end||':'||delivery_status||'='||state||'/pid_null='||(provider_message_id is null), ',') from dokene.provider_webhook_events where id in ($(paste -sd, "$OUTD/h04-parked.ids"))")"
  rec $C "H4 still SENT and unchanged after the park window" "$([ "$(mrow "$M")" = "$(cat "$OUTD/h04.row")" ] && echo PASS || echo FAIL)" "row=$(mrow "$M")"
  qa_finish
fi
S0=$(stub_sends); N0=$(msgs_total); R0=$(mrow "$M"); E0=$(mevents "$M"); I0=$(inbox_rows '')
printf '%s' "$R0" > "$OUTD/h04.row"
unchanged() { [ "$(mrow "$M")" = "$R0" ] && [ "$(mevents "$M")" = "$E0" ] && [ "$(msgs_total)" = "$N0" ] && [ "$(stub_sends)" = "$S0" ]; }
chk() { # label expected-http expected-inbox-delta
  rec $C "$1" "$([ "$WCODE" = "$2" ] && no_cookie && [ "$(inbox_rows '')" = $((I0 + $3)) ] && unchanged && echo PASS || echo FAIL)" \
    "http=$WCODE set_cookie=$(no_cookie && echo none || echo PRESENT) inbox_delta=$(( $(inbox_rows '') - I0 )) row=$(mrow "$M")"
}
D=$OUTD/h04-delivered.json; wh_body "$D" "$PNID_A" "$P:delivered:$(date +%s)"
wh_post "$D" "$(wh_sig "$D" QA336-WRONG-SECRET)";             chk "signature from another secret -> 403" 403 0
wh_post "$D" -;                                               chk "no signature header -> 403" 403 0
wh_post "$D" "sha1=$(wh_sig "$D" | cut -d= -f2 | cut -c1-40)"; chk "sha1= signature -> 403" 403 0
wh_post "$D" "sha256=zz$(wh_sig "$D" | cut -c10-)";           chk "non-hex sha256 value -> 403" 403 0
wh_post "$D" "$(wh_sig "$D")" -H "X-Hub-Signature-256: $(wh_sig "$D")"; chk "two signature headers -> 403" 403 0
T2=$OUTD/h04-tampered.json; sed 's/"delivered"/"read"/' "$D" > "$T2"
wh_post "$T2" "$(wh_sig "$D")";                               chk "valid signature of another body (status changed after signing) -> 403" 403 0
printf '{"object":"whatsapp_business_account","entry":[' > "$OUTD/h04-trunc.json"
wh_post "$OUTD/h04-trunc.json";                               chk "signed malformed JSON -> 400" 400 0
jq -c '.entry[0].changes[0].value.statuses[0].tenantId="'"$B"'"' "$D" > "$OUTD/h04-member.json"
wh_post "$OUTD/h04-member.json";                              chk "signed body with a supplied tenantId member -> 400, nothing stored" 400 0
jq -c '.object="page"' "$D" > "$OUTD/h04-object.json"
wh_post "$OUTD/h04-object.json";                              chk "signed body for another object type -> 400" 400 0
jq -c '.entry[0].changes[0].value.statuses[0].timestamp="yesterday"' "$D" > "$OUTD/h04-ts.json"
wh_post "$OUTD/h04-ts.json";                                  chk "signed body with a non-epoch timestamp -> 400" 400 0
head -c 4194305 /dev/zero > "$OUTD/h04-big.bin"
wh_post "$OUTD/h04-big.bin" "$(wh_sig "$OUTD/h04-big.bin")"; chk "declared length above the 4 MiB limit -> refused before reading" 403 0
rm -f "$OUTD/h04-big.bin"
wh_body "$OUTD/h04-unknown.json" 999999999999 "$P:delivered:$(date +%s)"
wh_post "$OUTD/h04-unknown.json";                             chk "signed report for an unknown phone number id -> 200, nothing stored" 200 0
wh_body "$OUTD/h04-unsup.json" "$PNID_A" "$P:deleted:$(date +%s)"
wh_post "$OUTD/h04-unsup.json";                               chk "signed unsupported status value -> 200, nothing stored" 200 0
# Wrong provider reference in tenant A, and H4's real reference reported through tenant B's verified number.
wh_body "$OUTD/h04-wrongref.json" "$PNID_A" "wamid.QA336-NO-SUCH-MESSAGE:delivered:$(date +%s)"
wh_post "$OUTD/h04-wrongref.json";                            chk "signed report with a wrong provider reference -> 200, parked, H4 unchanged" 200 1
wh_body "$OUTD/h04-cross.json" "$PNID_B" "$P:read:$(date +%s)"
wh_post "$OUTD/h04-cross.json";                               chk "H4's provider id reported through tenant B's number -> 200, parked under B, H4 unchanged" 200 2
psqlq "select ''''||id||'''' from dokene.provider_webhook_events where state='PENDING' and (provider_message_id='wamid.QA336-NO-SUCH-MESSAGE' or (provider_message_id='$P' and tenant_id='$B'))" > "$OUTD/h04-parked.ids"
rec $C "parked rows: wrong reference under A, cross-tenant under B, both PENDING" "$([ "$(wc -l < "$OUTD/h04-parked.ids")" = 2 ] && echo PASS || echo FAIL)" \
  "rows=$(psqlq "select string_agg(case when tenant_id='$A' then 'A' else 'B' end||':'||delivery_status||'='||state, ',') from dokene.provider_webhook_events where id in ($(paste -sd, "$OUTD/h04-parked.ids"))")"
# Verification challenge: a wrong token is 403 with no echo; no session either way.
out=$(curl -s -i --get --data-urlencode hub.mode=subscribe --data-urlencode hub.verify_token=QA336-WRONG --data-urlencode hub.challenge=QA336CHALLENGE "$BFF/webhooks/whatsapp")
rec $C "GET challenge with a wrong verify token -> 403, no echo, no Set-Cookie" "$(printf '%s' "$out" | head -1 | grep -q ' 403' && ! printf '%s' "$out" | grep -q QA336CHALLENGE && ! printf '%s' "$out" | grep -qi '^set-cookie:' && echo PASS || echo FAIL)" "status=$(printf '%s' "$out" | head -1 | tr -d '\r')"
# A webhook call never yields a usable session: the cookie-less route answers no Set-Cookie (all checks above), and the
# session endpoint stays anonymous for a client that only ever called the webhook.
J=$QA_TMP/jar-webhook-only.txt; rm -f "$J"
curl -s -o /dev/null -c "$J" -X POST -H 'Content-Type: application/json' -H "X-Hub-Signature-256: $(wh_sig "$D" QA336-WRONG-SECRET)" --data-binary "@$D" "$BFF/webhooks/whatsapp"
curl -s -o /dev/null -b "$J" -c "$J" -H 'Content-Type: application/json' -H "X-Hub-Signature-256: $(wh_sig "$OUTD/h04-unknown.json")" --data-binary "@$OUTD/h04-unknown.json" "$BFF/webhooks/whatsapp"
sc=$(curl -s -o /dev/null -w '%{http_code}' -b "$J" "$BFF/api/session")
# Cookie lines in a curl jar; "#HttpOnly_" lines are cookies too, only "# " comments and blank lines are not.
jar_cookies() { [ -f "$J" ] || { echo 0; return; }; grep -cv '^# \|^#$\|^$' "$J"; true; }
rec $C "a client that only called the webhook holds no cookie and /api/session answers 401" "$([ "$(jar_cookies)" = 0 ] && [ "$sc" = 401 ] && echo PASS || echo FAIL)" "cookies=$(jar_cookies) session=$sc"
rm -f "$J"
rec $C "H4 end state unchanged: same row, events, no new message, no provider request" "$(unchanged && echo PASS || echo FAIL)" "row=$(mrow "$M") events_delta=$(( $(mevents "$M") - E0 )) stub_delta=$(( $(stub_sends) - S0 ))"
qa_finish
