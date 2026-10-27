#!/usr/bin/env bash
# ABOUTME: Suite #149, forged webhook cases P3-HOOK-01 to P3-HOOK-11, 14 and 15 over real HTTP: raw byte signatures,
# ABOUTME: tenant resolution from the verified number only, replay, order, unknown ids, the challenge and the body bound.
cd "$(git rev-parse --show-toplevel)"; . docs/verification/issue-149-evidence/scripts/env149.sh
H=HTTP; stub_reset; stub_set send accept
O=$A_OPERATOR; SEC=$CANARY_APP_SECRET

sent_message() { # user tenant name -> "messageId providerMessageId"
  local ids; ids=$(ready_approved "$1" "$2" "$3") || return 1; local c p m; read -r c p m <<< "$ids"
  send "$1" "$2" "$m" >/dev/null; echo "$m $(echo "$BODY" | jq -r .providerMessageId)"; }
post_signed() { hook_post "$1" "$SEC" "${@:2}"; }
read -r MA WA <<< "$(sent_message $O "$A" "HOOK A $RUN")"; read -r MB WB <<< "$(sent_message $B_OPERATOR "$B" "HOOK B $RUN")"
chk P3-HOOK-15 $H "fixtures: MA-SENT and MB-SENT with provider ids" "SENT SENT yes" "$(msg_status "$MA" | cut -d/ -f1) $(msg_status "$MB" | cut -d/ -f1) $([ -n "$WA" ] && [ "$WA" != null ] && echo yes || echo no)"
GOOD=$(hook_body "$PN_A" "$WA" delivered)

echo "== P3-HOOK-01 no signature and wrong signatures, well formed and malformed bodies"
h0=$(hooks_n); m0=$(msg_status "$MA")
for b in "$GOOD" "not json at all" '{"object":"whatsapp_business_account","entry":"nope"}'; do
  hook_post "$b" -; c1=$CODE; hook_post "$b" "wrong-secret"; chk P3-HOOK-01 $H "body '${b:0:24}...': no signature 403, wrong signature 403" "403 403" "$c1 $CODE"; done
chk P3-HOOK-01 $H "nothing stored, MA unchanged" "$h0 $m0" "$(hooks_n) $(msg_status "$MA")"

echo "== P3-HOOK-02 the check is on the raw bytes"
sig=$(sign "$GOOD" "$SEC"); tampered="${GOOD/delivered/delivereD}"; spaced=$(echo "$GOOD" | sed 's/,/, /g')
raw -X POST "$BFF/webhooks/whatsapp" -H 'Content-Type: application/json' -H "X-Hub-Signature-256: $sig" --data-binary "$tampered"; c1=$CODE
raw -X POST "$BFF/webhooks/whatsapp" -H 'Content-Type: application/json' -H "X-Hub-Signature-256: $sig" --data-binary "$spaced"; c2=$CODE
hook_post "$GOOD" "CANARY-APP-SECRET-WRONG"; chk P3-HOOK-02 $H "wrong secret 403, one byte changed 403, whitespace changed 403" "403 403 403" "$CODE $c1 $c2"
chk P3-HOOK-02 $H "nothing stored" "$h0" "$(hooks_n)"

echo "== P3-HOOK-03 malformed signature headers"
hex=${sig#sha256=}
for s in "sha1=$hex" "$hex" "sha256=${hex:0:60}" "sha256=zz${hex:2}" "" "sha256="; do raw -X POST "$BFF/webhooks/whatsapp" -H 'Content-Type: application/json' -H "X-Hub-Signature-256: $s" --data-binary "$GOOD"
  chk P3-HOOK-03 $H "header [${s:0:16}...]: 403 with an empty body" "403 0" "$CODE $(printf '%s' "$BODY" | wc -c | tr -d ' ')"; done

echo "== P3-HOOK-04 a tenantId member in a signed event"
forged=$(hook_body "$PN_A" "$WA" delivered | sed "s/\"statuses\"/\"tenantId\":\"$B\",\"statuses\"/"); post_signed "$forged"
chk P3-HOOK-04 $H "400, nothing stored, MA and MB unchanged" "400 $h0 SENT SENT" "$CODE $(hooks_n) $(msg_status "$MA" | cut -d/ -f1) $(msg_status "$MB" | cut -d/ -f1)"

echo "== P3-HOOK-15 and P3-HOOK-06 a signed delivery for the verified number, then its replay"
a0=$(audit_n MESSAGE_DELIVERY_UPDATED "$A"); post_signed "$GOOD"; c1=$CODE; sleep 2
chk P3-HOOK-15 $H "200, MA DELIVERED, MB untouched, one inbox row in tenant A, none in B" "200 DELIVERED SENT 1 0" "$c1 $(msg_status "$MA" | cut -d/ -f1) $(msg_status "$MB" | cut -d/ -f1) $(( $(hooks_n "$A") - $(echo $h0) )) $(hooks_n "$B")"
v1=$(msg_status "$MA"); a1=$(audit_n MESSAGE_DELIVERY_UPDATED "$A"); post_signed "$GOOD"; sleep 2
chk P3-HOOK-06 $H "replay: 200, no state change, no second MESSAGE_DELIVERY_UPDATED audit row" "200 $v1 $a1" "$CODE $(msg_status "$MA") $(audit_n MESSAGE_DELIVERY_UPDATED "$A")"
chk P3-HOOK-15 $H "exactly one MESSAGE_DELIVERY_UPDATED audit row for the delivery" 1 "$(( a1 - a0 ))"

echo "== P3-HOOK-05 a signed event for PN-A naming tenant B's provider message id"
post_signed "$(hook_body "$PN_A" "$WB" delivered)"; c1=$CODE; sleep 2
chk P3-HOOK-05 $H "200, MB unchanged, the row is parked in tenant A, nothing in tenant B" "200 SENT 1 0" "$c1 $(msg_status "$MB" | cut -d/ -f1) $(psqlq "select count(*) from dokene.provider_webhook_events where tenant_id='$A' and provider_message_id='$WB'") $(psqlq "select count(*) from dokene.provider_webhook_events where tenant_id='$B' and provider_message_id='$WB'")"
# Follow this row by its id: rows parked by an earlier run in the same minute must not count.
HID=$(psqlq "select id from dokene.provider_webhook_events where tenant_id='$A' and provider_message_id='$WB'")
echo "  waiting for the park window (1 min) plus a sweep"; for i in $(seq 1 20); do st=$(psqlq "select state from dokene.provider_webhook_events where tenant_id='$A' and provider_message_id='$WB'"); [ -z "$st" ] && break; sleep 5; done
chk P3-HOOK-05 $H "after the park window the row is UNMATCHED with its provider message id cleared" "0 UNMATCHED true" "$(psqlq "select count(*) from dokene.provider_webhook_events where tenant_id='$A' and provider_message_id='$WB'") $(psqlq "select state||' '||(provider_message_id is null) from dokene.provider_webhook_events where id='$HID'")"
chk P3-HOOK-05 $H "MB still SENT" SENT "$(msg_status "$MB" | cut -d/ -f1)"

echo "== P3-HOOK-07 out of order reports never move a message backwards"
read -r MR WR <<< "$(sent_message $O "$A" "HOOK read $RUN")"; post_signed "$(hook_body "$PN_A" "$WR" delivered)"; post_signed "$(hook_body "$PN_A" "$WR" read)"; sleep 2
chk P3-HOOK-07 $H "fixture: message READ" READ "$(msg_status "$MR" | cut -d/ -f1)"
e0=$(events_n "$MR"); post_signed "$(hook_body "$PN_A" "$WR" delivered "$(( $(date +%s) + 1 ))")"; post_signed "$(hook_body "$PN_A" "$WR" sent "$(( $(date +%s) + 2 ))")"; post_signed "$(hook_body "$PN_A" "$WR" failed "$(( $(date +%s) + 3 ))")"; sleep 3
# The inbox deduplicates on tenant, provider message id and status, so the repeated "delivered" is a replay (P3-HOOK-03)
# and only "sent" and "failed" reach the sink, each as a non applied event.
chk P3-HOOK-07 $H "delivered, sent and failed after read: still READ, the two unseen statuses recorded as non applied events, no failure stored" "READ 2 " "$(msg_status "$MR" | cut -d/ -f1) $(( $(events_n "$MR") - e0 )) $(psqlq "select coalesce(failure_category,'') from dokene.outbound_messages where id='$MR'")"
read -r MD WD <<< "$(sent_message $O "$A" "HOOK delivered $RUN")"; post_signed "$(hook_body "$PN_A" "$WD" delivered)"; sleep 2; post_signed "$(hook_body "$PN_A" "$WD" failed "$(( $(date +%s) + 1 ))")"; sleep 2
chk P3-HOOK-07 $H "a late failure does not erase a delivery" "DELIVERED " "$(msg_status "$MD" | cut -d/ -f1) $(psqlq "select coalesce(failure_category,'') from dokene.outbound_messages where id='$MD'")"

echo "== P3-HOOK-08 unknown phone number id and unknown provider message id"
post_signed "$GOOD"; nb=$BODY; nc=$CODE; h1=$(hooks_n)
post_signed "$(hook_body 100000000000077 "$WA" delivered)"; chk P3-HOOK-08 $H "unknown number: same status and body as a normal event, nothing stored" "$nc same $h1" "$CODE $([ "$BODY" = "$nb" ] && echo same || echo different) $(hooks_n)"
post_signed "$(hook_body "$PN_A" "wamid.SYNTHETIC-unknown-$RUN" delivered)"; chk P3-HOOK-08 $H "unknown provider message id: same status and body, parked in tenant A" "$nc same 1" "$CODE $([ "$BODY" = "$nb" ] && echo same || echo different) $(psqlq "select count(*) from dokene.provider_webhook_events where tenant_id='$A' and provider_message_id='wamid.SYNTHETIC-unknown-$RUN'")"

echo "== P3-HOOK-09 the verification challenge"
L0=$(backend_log | wc -l)
raw "$BFF/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=wrong&hub.challenge=qa149-$RUN"; chk P3-HOOK-09 $H "wrong token: 403, challenge not echoed" "403 0" "$CODE $(echo "$BODY" | grep -c "qa149-$RUN")"
raw "$BFF/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=$CANARY_VERIFY&hub.challenge=qa149-$RUN"; chk P3-HOOK-09 $H "right token: 200 text/plain with only the challenge" "200 text/plain qa149-$RUN" "$CODE $(ctype | cut -d';' -f1) $BODY"
chk P3-HOOK-09 $H "the verify token is in no response and not in the backend log" "0 0" "$(echo "$BODY$HDRS" | grep -c CANARY-VERIFY) $(backend_log | tail -n +$((L0+1)) | grep -c CANARY-VERIFY)"

echo "== P3-HOOK-10 wrong schema and an oversized body"
post_signed '{"object":"whatsapp_business_account","entry":[{"id":"200000000000001","changes":[{"value":{"messaging_product":"whatsapp","statuses":"nope"},"field":"messages"}]}]}'; chk P3-HOOK-10 $H "signed but schema invalid: 400" 400 "$CODE"
head -c 4500000 /dev/zero | tr '\0' 'a' > "$QA_TMP/big.json"; t0=$(date +%s); raw -X POST "$BFF/webhooks/whatsapp" -H 'Content-Type: application/json' -H 'Expect:' -H "X-Hub-Signature-256: $sig" --data-binary "@$QA_TMP/big.json"; t1=$(date +%s)
chk P3-HOOK-10 $H "4.5 MB body: refused (403 or 413) within 10 s" "yes yes" "$([ "$CODE" = 403 ] || [ "$CODE" = 413 ] && echo yes || echo "$CODE") $([ $((t1-t0)) -le 10 ] && echo yes || echo "$((t1-t0))s")"

echo "== P3-HOOK-11 a session cookie proves nothing on the webhook"
hook_post "$GOOD" - -b "$QA_TMP/jar-$A_OWNER.txt"; chk P3-HOOK-11 $H "unsigned event with the owner's cookie: 403" 403 "$CODE"

echo "== P3-HOOK-14 timestamps far in the future and before the message existed"
read -r MT WT <<< "$(sent_message $O "$A" "HOOK time $RUN")"
post_signed "$(hook_body "$PN_A" "$WT" delivered "$(( $(date +%s) + 86400 * 365 ))")"; sleep 2
d1=$(psqlq "select extract(epoch from delivered_at)::bigint - extract(epoch from now())::bigint from dokene.outbound_messages where id='$MT'")
chk P3-HOOK-14 $H "a delivery a year in the future is stored bounded (within an hour of now)" yes "$([ -n "$d1" ] && [ "$d1" -lt 3600 ] && [ "$d1" -gt -3600 ] && echo yes || echo "offset=$d1")"
read -r MT2 WT2 <<< "$(sent_message $O "$A" "HOOK time2 $RUN")"; created=$(psqlq "select extract(epoch from created_at)::bigint from dokene.outbound_messages where id='$MT2'")
anchor0=$(psqlq "select p.last_outbound_message_date from dokene.customer_follow_up_policies p join dokene.outbound_messages m on m.customer_id=p.customer_id where m.id='$MT2'")
post_signed "$(hook_body "$PN_A" "$WT2" delivered "$(( created - 86400 * 30 ))")"; sleep 2
# Spec 0001 AC-14: the window is [created_at, now], so a pre-creation report lands on created_at exactly (which is
# before sent_at by the approval and dispatch time; the preparation run compared with sent_at and passed on rounding).
chk P3-HOOK-14 $H "a delivery dated a month before the message existed is stored at created_at (spec 0001 AC-14 window)" "DELIVERED true" "$(psqlq "select status||' '||(delivered_at = created_at) from dokene.outbound_messages where id='$MT2'")"
chk P3-HOOK-14 $H "the follow-up cadence anchor did not move backwards" "$anchor0" "$(psqlq "select p.last_outbound_message_date from dokene.customer_follow_up_policies p join dokene.outbound_messages m on m.customer_id=p.customer_id where m.id='$MT2'")"
summary
