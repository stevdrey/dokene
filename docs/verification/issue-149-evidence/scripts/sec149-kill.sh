#!/usr/bin/env bash
# ABOUTME: Suite #149, emergency disable cases P3-KILL-01 to P3-KILL-10 over real HTTP: the global deployment switch
# ABOUTME: (restarts with it off), the tenant switch, the unreadable switch and the disabled integration.
cd "$(git rev-parse --show-toplevel)"; . docs/verification/issue-149-evidence/scripts/env149.sh
H=HTTP; stub_reset; stub_set send accept
O=$A_OPERATOR; W=$A_OWNER
relogin() { for u in $ALL_USERS; do qa_login $u; done; }
sw() { req $1 "$2" PUT /api/integrations/whatsapp/outbound "{\"enabled\":$3}" -H "If-Match: $(integ_etag $1 "$2")"; }

echo "== P3-KILL-01, P3-KILL-03 and P3-KILL-10: global switch off (restart with the property unset / false)"
read -r c p MA <<< "$(ready_approved $O "$A" "KILL01 $RUN")"; read -r c2 p2 MB <<< "$(ready_approved $B_OPERATOR "$B" "KILL10 B $RUN")"
read -r MS WS <<< "$(ready_approved $O "$A" "KILL10 sent $RUN" | awk '{print $3}') -"; send $O "$A" "$MS" >/dev/null; WS=$(echo "$BODY" | jq -r .providerMessageId)
QA149_OUTBOUND=false bash docs/verification/issue-149-evidence/scripts/stack149.sh restart >/dev/null; relogin
S=$(snap); send $O "$A" "$MA"; refused P3-KILL-01 $H "send with the global switch off" OUTBOUND_DISABLED_GLOBALLY 409 "$S"; chk P3-KILL-01 $H "still APPROVED, no attempt" "APPROVED 0" "$(msg_status "$MA" | cut -d/ -f1) $(attempts_n "$MA")"
S=$(snap); send $B_OPERATOR "$B" "$MB"; refused P3-KILL-10 $H "tenant B send with the global switch off" OUTBOUND_DISABLED_GLOBALLY 409 "$S"
hook_post "$(hook_body "$PN_A" "$WS" delivered)" "$CANARY_APP_SECRET"; sleep 2; chk P3-KILL-10 $H "a delivery report for an already sent message is still recorded" "200 DELIVERED" "$CODE $(msg_status "$MS" | cut -d/ -f1)"
sw $W "$A" true; c1=$CODE; sw $W "$A" false; sw $W "$A" true
req $W "$A" PUT /api/integrations/whatsapp "$(jq -nc --arg p "$PN_A" --arg w "$WABA_A" '{phoneNumberId:$p,businessAccountId:$w,displayPhoneNumber:"+50600000000",enabled:true,secretRef:"qa149-token-a"}')" -H "If-Match: $(integ_etag $W "$A")"; c2=$CODE
S=$(snap); send $O "$A" "$MA"; refused P3-KILL-03 $H "after the tenant switch and the integration were written ($c1/$c2), the global switch still refuses" OUTBOUND_DISABLED_GLOBALLY 409 "$S"
for p in "PUT /api/integrations/whatsapp/outbound-global" "PUT /api/messaging/outbound" "PUT /api/settings/outbound" "POST /api/integrations/whatsapp/outbound/global"; do set -- $p; req $W "$A" $1 $2 '{"enabled":true}' -H 'If-Match: "0"'
  chk P3-KILL-03 $H "no route to flip the global switch: $1 $2" yes "$([ "$CODE" = 404 ] || [ "$CODE" = 405 ] && echo yes || echo "$CODE")"; done
rec P3-KILL-03 $H PASS "the screens half (row 10 of the scope) is a browser case; the SPA has no control for the global switch (checked in sec149-browser.mjs)"
bash docs/verification/issue-149-evidence/scripts/stack149.sh restart >/dev/null; relogin; restore_integration $W "$A" "$PN_A" "$WABA_A" qa149-token-a >/dev/null

echo "== P3-KILL-02 global on, tenant A off, tenant B on"
read -r c p MA <<< "$(ready_approved $O "$A" "KILL02 $RUN")"; read -r c2 p2 MB <<< "$(ready_approved $B_OPERATOR "$B" "KILL02 B $RUN")"
sw $W "$A" false; chk P3-KILL-02 $H "tenant A switch off" 200 "$CODE"
S=$(snap); send $O "$A" "$MA"; refused P3-KILL-02 $H "tenant A send" OUTBOUND_DISABLED_FOR_TENANT 409 "$S"
n0=$(stub_calls send); send $B_OPERATOR "$B" "$MB"; chk P3-KILL-02 $H "tenant B sends" "201 SENT 1" "$CODE $(echo "$BODY" | jq -r .status) $(stub_since send $n0)"
sw $W "$A" true

echo "== P3-KILL-04 the tenant switch takes effect at once and is audited"
read -r c p MA <<< "$(ready_approved $O "$A" "KILL04 $RUN")"; k0=$(audit_n OUTBOUND_KILL_SWITCH_CHANGED "$A")
sw $A_ADMIN "$A" false; S=$(snap); send $O "$A" "$MA"; refused P3-KILL-04 $H "send right after the admin flipped the switch off" OUTBOUND_DISABLED_FOR_TENANT 409 "$S"
chk P3-KILL-04 $H "the change is audited as OUTBOUND_KILL_SWITCH_CHANGED" 1 "$(( $(audit_n OUTBOUND_KILL_SWITCH_CHANGED "$A") - k0 ))"
sw $A_ADMIN "$A" true

echo "== P3-KILL-05 OPERATOR and VIEWER cannot touch the tenant switch"
for u in $O $A_VIEWER; do for v in false true; do S=$(snap); sw $u "$A" $v; denied P3-KILL-05 $H "$u sets $v" INTEGRATION_MANAGE "$S"; done; done
chk P3-KILL-05 $H "switch unchanged (on)" true "$(getj $W "$A" /api/integrations/whatsapp | jq -r .outboundEnabled)"

echo "== P3-KILL-06 tenant A admin against tenant B's switch"
S=$(snap); req $A_ADMIN "$B" PUT /api/integrations/whatsapp/outbound '{"enabled":false}' -H 'If-Match: "1"'; chk P3-KILL-06 $H "403 and nothing written" "403 ok" "$CODE $(unchanged "$S" any)"
read -r c2 p2 MB <<< "$(ready_approved $B_OPERATOR "$B" "KILL06 B $RUN")"; send $B_OPERATOR "$B" "$MB"; chk P3-KILL-06 $H "tenant B still sends" 201 "$CODE"

echo "== P3-KILL-07 the control state cannot be read (integration row missing)"
read -r PA_ SA_ <<< "$(ctx tenant "$A")"
rec P3-KILL-07 $H BLOCKED "not driven on the real stack: deleting the integration row needs a database role with DELETE on tenant_messaging_integrations (the runtime role has none) and would destroy the fixture; covered by WhatsAppOutboundSafetyControlTest (in process)"

echo "== P3-KILL-08 integration disabled with both switches on"
read -r c p MA <<< "$(ready_approved $O "$A" "KILL08 $RUN")"
req $W "$A" PUT /api/integrations/whatsapp "$(jq -nc --arg p "$PN_A" --arg w "$WABA_A" '{phoneNumberId:$p,businessAccountId:$w,displayPhoneNumber:"+50600000000",enabled:false,secretRef:"qa149-token-a"}')" -H "If-Match: $(integ_etag $W "$A")"
S=$(snap); send $O "$A" "$MA"; refused P3-KILL-08 $H "send with IA disabled" INTEGRATION_DISABLED 409 "$S"
restore_integration $W "$A" "$PN_A" "$WABA_A" qa149-token-a >/dev/null

echo "== P3-KILL-09 the whole policy runs again after the switch is turned back on"
read -r c p MA <<< "$(ready_approved $O "$A" "KILL09 $RUN")"; sw $W "$A" false; send $O "$A" "$MA"; c1=$CODE
dnc $O "$A" "$c" true >/dev/null; sw $W "$A" true; S=$(snap); send $O "$A" "$MA"
refused P3-KILL-09 $H "refused by the switch ($c1), do not contact set during the outage, switch back on: send refused by policy" DO_NOT_CONTACT 409 "$S"
summary
