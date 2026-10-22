#!/usr/bin/env bash
# ABOUTME: Suite #149, edited or stale approval cases P3-APPR-01 to P3-APPR-14 over real HTTP: stale ETags, missing
# ABOUTME: or weak preconditions, no edit route, extra JSON members, carried over approvals, mapping and sender changes.
cd "$(git rev-parse --show-toplevel)"; . docs/verification/issue-149-evidence/scripts/env149.sh
H=HTTP; stub_reset; stub_set send accept
O=$A_OPERATOR; W=$A_OWNER

echo "== P3-APPR-01 approve one version behind after a competing rejection"
read -r c p MP <<< "$(ready_pending $O "$A" "APPR01 $RUN")"; E1=$(msg_etag $O "$A" "$MP")
reject $W "$A" "$MP"; chk P3-APPR-01 $H "second member rejects" 201 "$CODE"
S=$(snap); approve $O "$A" "$MP" "qa149-$RUN-$RANDOM" "$E1"; refused P3-APPR-01 $H "approve with the old ETag" STALE_VERSION 409 "$S"

echo "== P3-APPR-02 reject and cancel one version behind"
read -r c p MP <<< "$(ready_pending $O "$A" "APPR02 $RUN")"; E1=$(msg_etag $O "$A" "$MP")
approve $W "$A" "$MP"; S=$(snap); reject $O "$A" "$MP" "qa149-$RUN-$RANDOM" "$E1"; refused P3-APPR-02 $H "reject pending with the old ETag (now approved)" STALE_VERSION 409 "$S"
S=$(snap); cancel $O "$A" "$MP" "qa149-$RUN-$RANDOM" "$E1"; refused P3-APPR-02 $H "cancel approved with the old ETag" STALE_VERSION 409 "$S"

echo "== P3-APPR-03 approve with no, any, weak and list If-Match"
read -r c p MP <<< "$(ready_pending $O "$A" "APPR03 $RUN")"
for v in "" "*" 'W/"1"' '"1", "2"'; do S=$(snap)
  if [ -z "$v" ]; then req $O "$A" POST "/api/messages/$MP/approvals" '{"decision":"APPROVE"}' -H "Idempotency-Key: qa149-$RUN-$RANDOM"
  else req $O "$A" POST "/api/messages/$MP/approvals" '{"decision":"APPROVE"}' -H "Idempotency-Key: qa149-$RUN-$RANDOM" -H "If-Match: $v"; fi
  refused P3-APPR-03 $H "If-Match [$v]" INVALID_INPUT 400 "$S"
done

echo "== P3-APPR-04 no edit route"
read -r c p MA <<< "$(ready_approved $O "$A" "APPR04 $RUN")"; before=$(psqlq "select status||'|'||body||'|'||recipient_phone||'|'||contact_id from dokene.outbound_messages where id='$MA'")
for m in PUT PATCH; do S=$(snap); req $W "$A" $m "/api/messages/$MA" '{"body":"edited","contactId":"00000000-0000-0000-0000-000000000001","status":"SENT"}' -H "If-Match: $(msg_etag $W "$A" "$MA")"
  chk P3-APPR-04 $H "$m /api/messages/{id}: 404 or 405, nothing written" "yes ok" "$([ "$CODE" = 404 ] || [ "$CODE" = 405 ] && echo yes || echo "$CODE") $(unchanged "$S" any)"; done
chk P3-APPR-04 $H "stored status, body, recipient and contact unchanged" "$before" "$(psqlq "select status||'|'||body||'|'||recipient_phone||'|'||contact_id from dokene.outbound_messages where id='$MA'")"

echo "== P3-APPR-05 approval with extra members"
read -r c p MP <<< "$(ready_pending $O "$A" "APPR05 $RUN")"; S=$(snap)
req $O "$A" POST "/api/messages/$MP/approvals" "{\"decision\":\"APPROVE\",\"body\":\"x\",\"recipientPhone\":\"+56911111111\",\"status\":\"SENT\",\"contactId\":\"$p\"}" -H "Idempotency-Key: qa149-$RUN-$RANDOM" -H "If-Match: $(msg_etag $O "$A" "$MP")"
refused P3-APPR-05 $H "unknown members rejected" INVALID_INPUT 400 "$S"
chk P3-APPR-05 $H "message still pending" PENDING_APPROVAL "$(msg_status "$MP" | cut -d/ -f1)"

echo "== P3-APPR-06 an approval does not carry over to a new message"
read -r CA PA M1 <<< "$(ready_approved $O "$A" "APPR06 $RUN")"; cancel $O "$A" "$M1"; submit $O "$A" "$CA" "$PA"; M2=$MSG
S=$(snap); send $O "$A" "$M2"; refused P3-APPR-06 $H "send of the unapproved M2" INVALID_TRANSITION 409 "$S"; chk P3-APPR-06 $H "no attempt row on M2" 0 "$(attempts_n "$M2")"

echo "== P3-APPR-07 submit against an old follow up policy version"
read -r CA PA <<< "$(mk_customer $O "$A" "APPR07 $RUN")"; make_due $O "$A" "$CA" "$PA"; v0=$(fpv $O "$A" "$CA")
req $O "$A" PUT "/api/customers/$CA/follow-up-policy" '{"cadenceDays":20,"explicitNextDate":null}' -H "If-Match: $v0"; chk P3-APPR-07 $H "policy moved to a new version" "200 yes" "$CODE $([ "$(fpv $O "$A" "$CA")" != "$v0" ] && echo yes || echo no)"
S=$(snap); submit $O "$A" "$CA" "$PA" "qa149-$RUN-$RANDOM" "$v0"; refused P3-APPR-07 $H "submit with the old policy version" STALE_VERSION 409 "$S"

echo "== P3-APPR-08 invalid transitions"
read -r c p MP <<< "$(ready_pending $O "$A" "APPR08a $RUN")"; reject $O "$A" "$MP"; S=$(snap); approve $O "$A" "$MP"; refused P3-APPR-08 $H "approve after reject" INVALID_TRANSITION 409 "$S"
read -r c p MP <<< "$(ready_pending $O "$A" "APPR08b $RUN")"; approve $O "$A" "$MP"; S=$(snap); approve $O "$A" "$MP"; refused P3-APPR-08 $H "approve twice with different keys" INVALID_TRANSITION 409 "$S"
S=$(snap); reject $O "$A" "$MP"; refused P3-APPR-08 $H "reject after approval" INVALID_TRANSITION 409 "$S"
read -r c p MA <<< "$(ready_approved $O "$A" "APPR08c $RUN")"; send $O "$A" "$MA"; chk P3-APPR-08 $H "control send" "201 SENT" "$CODE $(echo "$BODY" | jq -r .status)"
S=$(snap); cancel $O "$A" "$MA"; refused P3-APPR-08 $H "cancel after send" INVALID_TRANSITION 409 "$S"

echo "== P3-APPR-09 phone correction strands the pending message"
read -r CA PA MP <<< "$(ready_pending $O "$A" "APPR09 $RUN")"; cv=$(getj $O "$A" "/api/customers/$CA" | jq -r .version)
req $O "$A" PUT "/api/customers/$CA" "$(jq -nc --arg n "APPR09 $RUN" --arg p "$(rnd_phone)" --argjson v "$cv" '{displayName:$n,notes:null,phones:[{number:$p,region:"CL",primary:true}],version:$v}')"
chk P3-APPR-09 $H "customer phone corrected (contact replaced)" "200 yes" "$CODE $([ "$(getj $O "$A" "/api/customers/$CA" | jq -r '.phones[0].id')" != "$PA" ] && echo yes || echo no)"
a0=$(audit_n MESSAGE_APPROVED "$A"); S=$(snap); approve $O "$A" "$MP"; refused P3-APPR-09 $H "approve the stranded message" CONTACT_NOT_OWNED 409 "$S"
chk P3-APPR-09 $H "no approval row, no MESSAGE_APPROVED audit" "0 0" "$(psqlq "select count(*) from dokene.outbound_message_approvals where message_id='$MP'") $(( $(audit_n MESSAGE_APPROVED "$A") - a0 ))"
reject $O "$A" "$MP"; chk P3-APPR-09 $H "the stranded message can be rejected" 201 "$CODE"

echo "== P3-APPR-10 disabled mapping, then an edited and unrefreshed mapping"
read -r c p MA <<< "$(ready_approved $O "$A" "APPR10 $RUN")"
tput_() { req $W "$A" PUT /api/templates/GENERAL_FOLLOW_UP "$1" -H "If-Match: \"$(tmpl_version $W "$A")\""; }
tput_ '{"providerTemplateName":"qa149_general_follow_up","language":"es","parameterNames":["customer_name","message_body"],"enabled":false}'; chk P3-APPR-10 $H "TA disabled" 200 "$CODE"
S=$(snap); send $O "$A" "$MA"; refused P3-APPR-10 $H "send with TA disabled" TEMPLATE_NOT_MAPPED 409 "$S"
tput_ '{"providerTemplateName":"qa149_general_follow_up_v2","language":"es","parameterNames":["customer_name","message_body"],"enabled":true}'; chk P3-APPR-10 $H "TA renamed, not refreshed: not sendable" "200 false" "$CODE $(echo "$BODY" | jq -r .sendable)"
S=$(snap); send $O "$A" "$MA"; refused P3-APPR-10 $H "send with an unrefreshed edited mapping" TEMPLATE_NOT_MAPPED 409 "$S"
chk P3-APPR-10 $H "message still APPROVED, no attempt" "APPROVED 0" "$(msg_status "$MA" | cut -d/ -f1) $(attempts_n "$MA")"
restore_template $W "$A"

echo "== P3-APPR-13 mapping edited and refreshed again: the old approval is invalidated"
read -r c p MA <<< "$(ready_approved $O "$A" "APPR13 $RUN")"
tput_ '{"providerTemplateName":"qa149_general_follow_up_v3","language":"es","parameterNames":["customer_name","message_body"],"enabled":true}'
req $W "$A" POST /api/templates/GENERAL_FOLLOW_UP/approval-refresh - -H "If-Match: $(etag)"; chk P3-APPR-13 $H "edited mapping refreshed and sendable again" "200 true" "$CODE $(echo "$BODY" | jq -r .sendable)"
S=$(snap); send $O "$A" "$MA"; refused P3-APPR-13 $H "send with an approval bound to the old mapping" APPROVAL_INVALIDATED 409 "$S"
chk P3-APPR-13 $H "message still APPROVED, no attempt" "APPROVED 0" "$(msg_status "$MA" | cut -d/ -f1) $(attempts_n "$MA")"
restore_template $W "$A"

echo "== P3-APPR-11 disabled integration, changed secret reference, rotated credential, each without a new proof"
iput() { req $W "$A" GET /api/integrations/whatsapp -; req $W "$A" PUT /api/integrations/whatsapp "$1" -H "If-Match: $(etag)"; }
read -r c p MA <<< "$(ready_approved $O "$A" "APPR11 $RUN")"
iput "$(jq -nc --arg p "$PN_A" --arg w "$WABA_A" '{phoneNumberId:$p,businessAccountId:$w,displayPhoneNumber:"+50600000000",enabled:false,secretRef:"qa149-token-a"}')"; chk P3-APPR-11 $H "IA disabled" "200 false" "$CODE $(echo "$BODY" | jq -r .enabled)"
S=$(snap); send $O "$A" "$MA"; refused P3-APPR-11 $H "send with IA disabled" INTEGRATION_DISABLED 409 "$S"
iput "$(jq -nc --arg p "$PN_A" --arg w "$WABA_A" '{phoneNumberId:$p,businessAccountId:$w,displayPhoneNumber:"+50600000000",enabled:true,secretRef:"qa149-token-a-rotated"}')"; chk P3-APPR-11 $H "IA re-enabled with a new secretRef: proof cleared" "200 false" "$CODE $(echo "$BODY" | jq -r .webhookVerified)"
S=$(snap); send $O "$A" "$MA"; refused P3-APPR-11 $H "send after a secretRef change without a proof" INTEGRATION_DISABLED 409 "$S"
restore_integration $W "$A" "$PN_A" "$WABA_A" qa149-token-a
req $W "$A" POST /api/integrations/whatsapp/credential-rotations - -H "If-Match: $(integ_etag $W "$A")"; chk P3-APPR-11 $H "credential rotated: proof cleared" "200 false" "$CODE $(echo "$BODY" | jq -r .webhookVerified)"
S=$(snap); send $O "$A" "$MA"; refused P3-APPR-11 $H "send after a rotation without a proof" INTEGRATION_DISABLED 409 "$S"
chk P3-APPR-11 $H "message still APPROVED, no attempt, no provider call" "APPROVED 0" "$(msg_status "$MA" | cut -d/ -f1) $(attempts_n "$MA")"
restore_integration $W "$A" "$PN_A" "$WABA_A" qa149-token-a

echo "== P3-APPR-14 rotated and proven again: a new sender needs a new approval"
read -r c p MA <<< "$(ready_approved $O "$A" "APPR14 $RUN")"
req $W "$A" POST /api/integrations/whatsapp/credential-rotations - -H "If-Match: $(integ_etag $W "$A")"
req $W "$A" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: $(etag)"; chk P3-APPR-14 $H "rotated and proven again" "200 true" "$CODE $(echo "$BODY" | jq -r .webhookVerified)"
S=$(snap); send $O "$A" "$MA"; refused P3-APPR-14 $H "send with an approval bound to the old credential revision" APPROVAL_INVALIDATED 409 "$S"
read -r c p MA2 <<< "$(ready_approved $O "$A" "APPR14b $RUN")"; n0=$(stub_calls send); send $O "$A" "$MA2"
chk P3-APPR-14 $H "control: a message approved after the rotation sends with the token the stub knows as A" "201 1 A" "$CODE $(stub_since send $n0) $(tail -1 "$STUBDIR/requests.log" | grep -o 'token=[A-Za-z]*' | cut -d= -f2)"

rec P3-APPR-12 $H BLOCKED "POST /api/messages/{id}/resolutions is not deployed on this commit; T12 is owed by #138"
summary
