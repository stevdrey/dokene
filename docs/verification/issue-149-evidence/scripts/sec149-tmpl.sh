#!/usr/bin/env bash
# ABOUTME: Suite #149, template approval evidence cases P3-TMPL-01 to P3-TMPL-08 over real HTTP with the Graph stub
# ABOUTME: answering the approval refresh: operator asserted approval, exact name and language, expiry, provider faults.
cd "$(git rev-parse --show-toplevel)"; . docs/verification/issue-149-evidence/scripts/env149.sh
H=HTTP; stub_reset; stub_set send accept; stub_set templates approved
O=$A_OPERATOR; W=$A_OWNER
tv() { getj $1 "$2" /api/templates | jq -r --arg i "$3" '.items[]|select(.templateIntent==$i)|.version // "null"'; }
tput() { # writer tenant intent json
  local v; v=$(tv $1 "$2" "$3"); if [ "$v" != null ]; then req $1 "$2" PUT "/api/templates/$3" "$4" -H "If-Match: \"$v\""; else req $1 "$2" PUT "/api/templates/$3" "$4" -H 'If-None-Match: *'; fi; }
trefresh() { req $1 "$2" POST "/api/templates/$3/approval-refresh" - -H "If-Match: \"$(tv $1 "$2" "$3")\""; }
submit_intent() { # user tenant customer contact intent
  req "$1" "$2" POST "/api/customers/$3/messages" "$(jq -nc --arg c "$4" --arg i "$5" '{contactId:$c,action:"GENERAL_CHECK_IN",templateIntent:$i,body:"Hola",locale:"es-419",origin:"MANUAL"}')" -H "Idempotency-Key: qa149-$RUN-$RANDOM$RANDOM" -H "If-Match: $(fpv "$1" "$2" "$3")"; MSG=$(echo "$BODY" | jq -r '.messageId // empty'); }
read -r CA PA <<< "$(mk_customer $O "$A" "TMPL $RUN")"; make_due $O "$A" "$CA" "$PA"

echo "== P3-TMPL-01 a member cannot assert approval"
for extra in '"approved":true' '"approvalStatus":"APPROVED"' '"providerTemplateId":"300000000000001"'; do S=$(snap)
  tput $W "$A" REPEAT_PURCHASE "{\"providerTemplateName\":\"qa149_repeat\",\"language\":\"es\",\"parameterNames\":[\"customer_name\"],\"enabled\":true,$extra}"
  refused P3-TMPL-01 $H "PUT with $extra" INVALID_INPUT 400 "$S"; done
S=$(snap); submit_intent $O "$A" "$CA" "$PA" REPEAT_PURCHASE; refused P3-TMPL-01 $H "submit for the unmapped intent" TEMPLATE_NOT_MAPPED 409 "$S"

echo "== P3-TMPL-02 only the refresh records evidence, with the tenant credential"
tput $W "$A" REPEAT_PURCHASE '{"providerTemplateName":"qa149_repeat","language":"es","parameterNames":["customer_name","message_body"],"enabled":true}'
chk P3-TMPL-02 $H "mapping stored, UNVERIFIED and not sendable" "200 UNVERIFIED false" "$CODE $(echo "$BODY" | jq -r '.approvalStatus+" "+(.sendable|tostring)')"
a0=$(audit_n TEMPLATE_MAPPING_UPDATED "$A"); n0=$(stub_calls templates); trefresh $O "$A" REPEAT_PURCHASE
chk P3-TMPL-02 $H "OPERATOR refresh: 200, APPROVED, sendable" "200 APPROVED true" "$CODE $(echo "$BODY" | jq -r '.approvalStatus+" "+(.sendable|tostring)')"
chk P3-TMPL-02 $H "one TEMPLATE_MAPPING_UPDATED audit row, one provider call with the tenant A token" "1 1 A" "$(( $(audit_n TEMPLATE_MAPPING_UPDATED "$A") - a0 )) $(stub_since templates $n0) $(tail -1 "$STUBDIR/requests.log" | grep -o 'token=[A-Za-z]*' | cut -d= -f2)"
chk P3-TMPL-02 $H "the provider was asked for exactly the mapped name and language" "qa149_repeat es" "$(cat "$STUBDIR/last-templates.query")"
chk P3-TMPL-02 $H "the audit row carries no provider text (no columns beyond the mapping id and enabled flag)" "" "$(psqlq "select coalesce(denial_reason,'')||coalesce(failure_category,'') from dokene.audit_events where event_type='TEMPLATE_MAPPING_UPDATED' order by occurred_at desc limit 1")"

echo "== P3-TMPL-03 only the exact name and language pair is evidence"
for mode in otherLanguage otherName; do stub_set templates $mode; trefresh $O "$A" REPEAT_PURCHASE
  chk P3-TMPL-03 $H "provider answers $mode: NOT_FOUND, not sendable" "200 NOT_FOUND false" "$CODE $(echo "$BODY" | jq -r '.approvalStatus+" "+(.sendable|tostring)')"; done
stub_set templates approved

echo "== P3-TMPL-04 evidence belongs to one business account"
read -r c p MA <<< "$(ready_approved $O "$A" "TMPL04 $RUN")"
req $W "$A" GET /api/integrations/whatsapp -; req $W "$A" PUT /api/integrations/whatsapp "$(jq -nc --arg p "$PN_A" '{phoneNumberId:$p,businessAccountId:"200000000000009",displayPhoneNumber:"+50600000000",enabled:true,secretRef:"qa149-token-a"}')" -H "If-Match: $(etag)"
req $W "$A" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: $(etag)"; chk P3-TMPL-04 $H "IA moved to another business account and proven again" "200 true" "$CODE $(echo "$BODY" | jq -r .webhookVerified)"
S=$(snap); send $O "$A" "$MA"; chk P3-TMPL-04 $H "send refused (409) with a closed code, nothing written" "409 code,status,title,type ok" "$CODE $(problem_keys) $(unchanged "$S")"; rec P3-TMPL-04 $H PASS "refusal code observed: $(problem_code)"
restore_integration $W "$A" "$PN_A" "$WABA_A" qa149-token-a; restore_template $W "$A"

echo "== P3-TMPL-05 evidence expires (approval-max-age lowered to 20s for this case)"
QA149_TEMPLATE_MAX_AGE=20s bash docs/verification/issue-149-evidence/scripts/stack149.sh restart >/dev/null; for u in $ALL_USERS; do qa_login $u; done
restore_template $W "$A" >/dev/null
read -r c p MP <<< "$(ready_pending $O "$A" "TMPL05p $RUN")"; read -r c2 p2 MA <<< "$(ready_approved $O "$A" "TMPL05a $RUN")"
chk P3-TMPL-05 $H "fresh evidence: pending and approved messages exist" "yes" "$([ -n "$MP" ] && [ -n "$MA" ] && echo yes || echo no)"
echo "  waiting 25 s for the evidence to expire"; sleep 25
S=$(snap); submit $O "$A" "$CA" "$PA"; refused P3-TMPL-05 $H "submit after expiry" TEMPLATE_NOT_MAPPED 409 "$S"
S=$(snap); approve $O "$A" "$MP"; refused P3-TMPL-05 $H "approve after expiry" TEMPLATE_NOT_MAPPED 409 "$S"
S=$(snap); send $O "$A" "$MA"; refused P3-TMPL-05 $H "send after expiry" TEMPLATE_NOT_MAPPED 409 "$S"
bash docs/verification/issue-149-evidence/scripts/stack149.sh restart >/dev/null; for u in $ALL_USERS; do qa_login $u; done; restore_template $W "$A" >/dev/null

echo "== P3-TMPL-06 provider faults during a refresh drop the evidence and leak nothing"
for mode in hang error500 redirect canaryError; do
  restore_template $W "$A" >/dev/null; stub_set templates $mode; L0=$(backend_log | wc -l)
  trefresh $O "$A" GENERAL_FOLLOW_UP; chk P3-TMPL-06 $H "$mode: 503 PROVIDER_UNAVAILABLE" "503 ok" "$CODE $(problem_ok PROVIDER_UNAVAILABLE)"
  chk P3-TMPL-06 $H "$mode: evidence dropped, not sendable" "UNVERIFIED false" "$(getj $O "$A" /api/templates | jq -r '.items[]|select(.templateIntent=="GENERAL_FOLLOW_UP")|.approvalStatus+" "+(.sendable|tostring)')"
  chk P3-TMPL-06 $H "$mode: no canary in the response or in the backend log since the call" "0 0" "$(echo "$BODY$HDRS" | grep -c 'CANARY') $(backend_log | tail -n +$((L0+1)) | grep -c 'CANARY')"
done
stub_set templates approved; restore_template $W "$A"

echo "== P3-TMPL-07 the stored status follows the provider"
for mode in rejected paused deleted; do stub_set templates $mode; trefresh $O "$A" GENERAL_FOLLOW_UP
  chk P3-TMPL-07 $H "$mode: status ${mode^^}, not sendable" "200 ${mode^^} false" "$CODE $(echo "$BODY" | jq -r '.approvalStatus+" "+(.sendable|tostring)')"
  read -r c p <<< "$(mk_customer $O "$A" "TMPL07 $mode $RUN")"; make_due $O "$A" "$c" "$p"; S=$(snap); submit $O "$A" "$c" "$p"; refused P3-TMPL-07 $H "$mode: submit refused" TEMPLATE_NOT_MAPPED 409 "$S"
done
stub_set templates approved; restore_template $W "$A"

echo "== P3-TMPL-08 evidence is per tenant"
read -r CB PB <<< "$(mk_customer $B_OPERATOR "$B" "TMPL08 $RUN")"; make_due $B_OPERATOR "$B" "$CB" "$PB"
S=$(snap); submit_intent $B_OPERATOR "$B" "$CB" "$PB" REPEAT_PURCHASE; refused P3-TMPL-08 $H "tenant B submit for an intent only tenant A mapped" TEMPLATE_NOT_MAPPED 409 "$S"
summary
