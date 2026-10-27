#!/usr/bin/env bash
# ABOUTME: Suite #149, cross tenant cases P3-IDOR-01 to P3-IDOR-10 over real HTTP: tenant A actors asking for tenant B
# ABOUTME: objects by id, header replacement, shared idempotency keys, and the integration number conflict.
cd "$(git rev-parse --show-toplevel)"; . docs/verification/issue-149-evidence/scripts/env149.sh
H=HTTP; stub_reset; stub_set send accept
RANDOM_UUID=$(uuidgen | tr 'A-Z' 'a-z')

echo "== fixtures: MB-PENDING in tenant B, CA1 and CA2 in tenant A"
read -r CB1 PB1 MB <<< "$(ready_pending $B_OPERATOR "$B" "IDOR B1 $RUN")"
read -r CA1 PA1 <<< "$(mk_customer $A_OPERATOR "$A" "IDOR A1 $RUN")"; make_due $A_OPERATOR "$A" "$CA1" "$PA1"
read -r CA2 PA2 <<< "$(mk_customer $A_OPERATOR "$A" "IDOR A2 $RUN")"; make_due $A_OPERATOR "$A" "$CA2" "$PA2"
MB_BEFORE=$(msg_status "$MB"); EB_BEFORE=$(events_n "$MB")

echo "== P3-IDOR-01 read tenant B message from tenant A"
req $A_OPERATOR "$A" GET "/api/messages/$RANDOM_UUID" -; rb=$BODY; rc=$CODE
req $A_OPERATOR "$A" GET "/api/messages/$MB" -; chk P3-IDOR-01 $H "GET MB-PENDING: 404 NOT_FOUND, same body as a random UUID" "404 ok same" "$CODE $(problem_ok NOT_FOUND) $([ "$BODY" = "$rb" ] && [ "$rc" = "$CODE" ] && echo same || echo different)"
req $A_OPERATOR "$A" GET "/api/messages/$MB/events" -; chk P3-IDOR-01 $H "GET MB-PENDING events: 404 NOT_FOUND" "404 ok" "$CODE $(problem_ok NOT_FOUND)"

echo "== P3-IDOR-02 lists never show tenant B"
req $A_OPERATOR "$A" GET "/api/messages?customerId=$CB1" -; chk P3-IDOR-02 $H "list filtered by CB1: 200 with no items" "200 0" "$CODE $(echo "$BODY" | jq '.items|length')"
seen=0; cursor=""; pages=0
while :; do req $A_OPERATOR "$A" GET "/api/messages?limit=5${cursor:+&cursor=$cursor}" -; pages=$((pages+1))
  seen=$(( seen + $(echo "$BODY" | jq --arg b "$MB" --arg c "$CB1" '[.items[]|select(.messageId==$b or .customerId==$c)]|length') ))
  cursor=$(echo "$BODY" | jq -r '.nextCursor // empty'); [ -z "$cursor" ] && break; [ $pages -gt 200 ] && break; done
chk P3-IDOR-02 $H "unfiltered list, $pages pages: no tenant B item" 0 "$seen"

echo "== P3-IDOR-03 submit against a tenant B customer"
S=$(snap); submit $A_OPERATOR "$A" "$CB1" "$PB1" "qa149-$RUN-idor03" '"0"'; notfound P3-IDOR-03 $H "submit for CB1 from tenant A (If-Match \"0\" supplied, since the policy read is itself not found)" "$S"

echo "== P3-IDOR-04 approve, reject, cancel against MB-PENDING"
for op in approve reject cancel; do S=$(snap); $op $A_OPERATOR "$A" "$MB" qa149-$RUN-$RANDOM '"1"'; notfound P3-IDOR-04 $H "$op MB-PENDING from tenant A" "$S"; done
chk P3-IDOR-04 $H "MB-PENDING keeps its status/version and events" "$MB_BEFORE $EB_BEFORE" "$(msg_status "$MB") $(events_n "$MB")"

echo "== P3-IDOR-05 send and resolve tenant B messages as A-ADMIN"
read -r CB2 PB2 MB2 <<< "$(ready_approved $B_OPERATOR "$B" "IDOR B2 $RUN")"
S=$(snap); send $A_ADMIN "$A" "$MB2" qa149-$RUN-$RANDOM '"2"'; notfound P3-IDOR-05 $H "send of a tenant B approved message" "$S"
chk P3-IDOR-05 $H "no attempt row on the tenant B message" 0 "$(attempts_n "$MB2")"
read -r CB3 PB3 MB3 <<< "$(ready_unknown $B_OPERATOR "$B" "IDOR B3 $RUN")"; MB3_BEFORE=$(msg_status "$MB3")
S=$(snap); resolve $A_ADMIN "$A" "$MB3" FAILED "qa149-$RUN-$RANDOM" "\"$(echo "$MB3_BEFORE" | cut -d/ -f2)\""
notfound P3-IDOR-05 $H "resolution of a tenant B message in SENDING with unknown outcome (B's current version as If-Match)" "$S"
chk P3-IDOR-05 $H "tenant B message unchanged, no resolution row" "$MB3_BEFORE 0" "$(msg_status "$MB3") $(resolutions_n "$MB3")"

echo "== P3-IDOR-06 tenant A member sends X-Tenant-Id of tenant B"
for p in "GET /api/messages" "GET /api/messages/$MB" "POST /api/messages/$MB/approvals" "POST /api/customers/$CB1/messages" "GET /api/templates" "GET /api/integrations/whatsapp" "PUT /api/integrations/whatsapp/outbound"; do
  set -- $p; S=$(snap); d='{"enabled":false}'; [ "$1" = GET ] && d=-
  req $A_OPERATOR "$B" $1 $2 "$d" -H "Idempotency-Key: qa149-$RUN-$RANDOM" -H 'If-Match: "1"'
  chk P3-IDOR-06 $H "A-OPERATOR with tenant B header, $1 $2: 403 and nothing written" "403 ok" "$CODE $(unchanged "$S" any)"
done

echo "== P3-IDOR-07 submit with a contact of another customer"
S=$(snap); submit $A_OPERATOR "$A" "$CA1" "$PA2"; notfound P3-IDOR-07 $H "CA1 with CA2's contact" "$S"; b1=$BODY
S=$(snap); submit $A_OPERATOR "$A" "$CA1" "$PB1"; notfound P3-IDOR-07 $H "CA1 with CB1's contact" "$S"; b2=$BODY
submit $A_OPERATOR "$A" "$CA1" "$RANDOM_UUID"; chk P3-IDOR-07 $H "the three not found bodies are identical to the random UUID one" "same same" "$([ "$b1" = "$BODY" ] && echo same || echo diff) $([ "$b2" = "$BODY" ] && echo same || echo diff)"

echo "== P3-IDOR-08 the same idempotency key in both tenants"
K1=qa149-$RUN-shared-key
submit $A_OPERATOR "$A" "$CA1" "$PA1" "$K1"; MA=$MSG; ca=$CODE; MA_BEFORE=$(msg_status "$MA")
read -r CB3 PB3 <<< "$(mk_customer $B_OPERATOR "$B" "IDOR B3 $RUN")"; make_due $B_OPERATOR "$B" "$CB3" "$PB3"
submit $B_OPERATOR "$B" "$CB3" "$PB3" "$K1"
chk P3-IDOR-08 $H "A submit 201, B submit with the same key 201 and a different message" "201 201 different" "$ca $CODE $([ "$MSG" != "$MA" ] && [ -n "$MSG" ] && echo different || echo same)"
chk P3-IDOR-08 $H "the tenant B message belongs to CB3, not to the tenant A customer" "$CB3" "$(echo "$BODY" | jq -r .customerId)"
chk P3-IDOR-08 $H "tenant A message untouched (status/version/outcomeUnknown)" "$MA_BEFORE" "$(msg_status "$MA")"

echo "== P3-IDOR-09 tenant A claims tenant B's phone number id"
req $A_ADMIN "$A" GET /api/integrations/whatsapp -; chk P3-IDOR-09 $H "A-ADMIN reads only tenant A data" "$PN_A $WABA_A" "$(echo "$BODY" | jq -r '.phoneNumberId+" "+.businessAccountId')"
putnum() { req $A_ADMIN "$A" GET /api/integrations/whatsapp -; req $A_ADMIN "$A" PUT /api/integrations/whatsapp "$(jq -nc --arg p "$1" --arg w "$WABA_A" '{phoneNumberId:$p,businessAccountId:$w,displayPhoneNumber:"+50600000000",enabled:true,secretRef:"qa149-token-a"}')" -H "If-Match: $(etag)"; }
putnum 100000000000099; unusedb=$(echo "$BODY" | jq -c '{enabled,outboundEnabled,webhookVerified}'); uc=$CODE
putnum "$PN_B"; chk P3-IDOR-09 $H "PUT PN-B into tenant A: same 200 and shape as an unused number, unverified claim" "$uc $unusedb" "$CODE $(echo "$BODY" | jq -c '{enabled,outboundEnabled,webhookVerified}')"
stub_set phones owned; S=$(snap); req $A_ADMIN "$A" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: $(etag)"
chk P3-IDOR-09 $H "ownership proof for PN-B in tenant A refused with 400 INVALID_INPUT" "400 ok" "$CODE $(problem_ok INVALID_INPUT)"
stub_set phones notOwned; req $A_ADMIN "$A" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: $(integ_etag $A_ADMIN "$A")"; np=$CODE; npb=$(problem_ok INVALID_INPUT); stub_set phones owned
chk P3-IDOR-09 $H "the NotProven answer is identical (400 INVALID_INPUT)" "400 ok" "$np $npb"
chk P3-IDOR-09 $H "tenant A row stays unverified, tenant B stays verified on PN-B" "false true $PN_B" "$(getj $A_ADMIN "$A" /api/integrations/whatsapp | jq -r .webhookVerified) $(getj $B_OWNER "$B" /api/integrations/whatsapp | jq -r '(.webhookVerified|tostring)+" "+.phoneNumberId')"
read -r cid pid m <<< "$(ready_approved $A_OPERATOR "$A" "IDOR09 send $RUN")"; n0=$(stub_calls send); send $A_OPERATOR "$A" "$m"
chk P3-IDOR-09 $H "a tenant A send on the unproven claim is refused with no provider call" "409 0" "$CODE $(stub_since send $n0)"
restore_integration $A_OWNER "$A" "$PN_A" "$WABA_A" qa149-token-a

echo "== P3-IDOR-10 templates are per tenant"
vb=$(tmpl_version $B_OWNER "$B")
req $A_OPERATOR "$A" GET /api/templates -; chk P3-IDOR-10 $H "A reads a catalogue of 5 intents, GENERAL_FOLLOW_UP mapped" "200 5 true" "$CODE $(echo "$BODY" | jq '.items|length') $(echo "$BODY" | jq -r '.items[]|select(.templateIntent=="GENERAL_FOLLOW_UP")|.mapped')"
va=$(getj $A_OPERATOR "$A" /api/templates | jq -r '.items[]|select(.templateIntent=="SEASONAL_EVENT")|.version // "null"')
body='{"providerTemplateName":"qa149_seasonal_idor","language":"es","parameterNames":["customer_name"],"enabled":true}'
if [ "$va" != null ]; then req $A_OPERATOR "$A" PUT /api/templates/SEASONAL_EVENT "$body" -H "If-Match: \"$va\""; else req $A_OPERATOR "$A" PUT /api/templates/SEASONAL_EVENT "$body" -H 'If-None-Match: *'; fi
chk P3-IDOR-10 $H "A writes its SEASONAL_EVENT mapping" 200 "$CODE"
chk P3-IDOR-10 $H "tenant B GENERAL_FOLLOW_UP version unchanged and SEASONAL_EVENT still unmapped in B" "$vb false" "$(tmpl_version $B_OWNER "$B") $(getj $B_OWNER "$B" /api/templates | jq -r '.items[]|select(.templateIntent=="SEASONAL_EVENT")|.mapped')"
summary
