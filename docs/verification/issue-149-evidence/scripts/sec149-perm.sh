#!/usr/bin/env bash
# ABOUTME: Suite #149, role and permission cases P3-PERM-01 to P3-PERM-19 over real HTTP against the local stack.
# ABOUTME: Every denial is checked for the exact problem body, one audit row and no business, event, key or attempt row.
cd "$(git rev-parse --show-toplevel)"; . docs/verification/issue-149-evidence/scripts/env149.sh
H=HTTP; stub_reset

echo "== P3-PERM-01 reads for the four roles"
read -r CA PA MP <<< "$(ready_pending $A_OPERATOR "$A" "PERM01 $RUN")"
for u in $A_OWNER $A_ADMIN $A_OPERATOR $A_VIEWER; do
  req $u "$A" GET /api/messages -; c1=$CODE; req $u "$A" GET "/api/messages/$MP" -; c2=$CODE; req $u "$A" GET "/api/messages/$MP/events" -; c3=$CODE
  chk P3-PERM-01 $H "$u list, read, events" "200 200 200" "$c1 $c2 $c3"
done
cancel $A_OPERATOR "$A" "$MP"

echo "== P3-PERM-02 submit: OWNER, ADMIN, OPERATOR allow; VIEWER denied"
for u in $A_OWNER $A_ADMIN $A_OPERATOR; do
  read -r cid pid <<< "$(mk_customer $A_OPERATOR "$A" "PERM02 $u $RUN")"; make_due $A_OPERATOR "$A" "$cid" "$pid"
  submit $u "$A" "$cid" "$pid"; chk P3-PERM-02 $H "$u submit" 201 "$CODE"; [ "$CODE" = 201 ] && cancel $u "$A" "$MSG"
done
read -r cid pid <<< "$(mk_customer $A_OPERATOR "$A" "PERM02 viewer $RUN")"; make_due $A_OPERATOR "$A" "$cid" "$pid"
S=$(snap); submit $A_VIEWER "$A" "$cid" "$pid"; denied P3-PERM-02 $H "VIEWER submit" MESSAGE_DRAFT "$S"

echo "== P3-PERM-03 cancel a pending message: allowed roles; VIEWER denied"
for u in $A_OWNER $A_ADMIN $A_OPERATOR; do
  read -r cid pid m <<< "$(ready_pending $A_OPERATOR "$A" "PERM03 $u $RUN")"; cancel $u "$A" "$m"; chk P3-PERM-03 $H "$u cancel pending" 201 "$CODE"
done
read -r CA PA MP <<< "$(ready_pending $A_OPERATOR "$A" "PERM03 viewer $RUN")"
S=$(snap); cancel $A_VIEWER "$A" "$MP"; denied P3-PERM-03 $H "VIEWER cancel pending" MESSAGE_DRAFT "$S"

echo "== P3-PERM-04 approve or reject: allowed roles; VIEWER denied"
for u in $A_OWNER $A_ADMIN $A_OPERATOR; do
  read -r cid pid m <<< "$(ready_pending $A_OPERATOR "$A" "PERM04a $u $RUN")"; approve $u "$A" "$m"; chk P3-PERM-04 $H "$u approve" 201 "$CODE"; cancel $u "$A" "$m"
  read -r cid pid m <<< "$(ready_pending $A_OPERATOR "$A" "PERM04r $u $RUN")"; reject $u "$A" "$m"; chk P3-PERM-04 $H "$u reject" 201 "$CODE"
done
S=$(snap); approve $A_VIEWER "$A" "$MP"; denied P3-PERM-04 $H "VIEWER approve" MESSAGE_APPROVE "$S"
S=$(snap); reject $A_VIEWER "$A" "$MP"; denied P3-PERM-04 $H "VIEWER reject" MESSAGE_APPROVE "$S"

echo "== P3-PERM-05 cancel an approved message: allowed roles; VIEWER denied"
for u in $A_OWNER $A_ADMIN $A_OPERATOR; do
  read -r cid pid m <<< "$(ready_approved $A_OPERATOR "$A" "PERM05 $u $RUN")"; cancel $u "$A" "$m"; chk P3-PERM-05 $H "$u cancel approved" 201 "$CODE"
done
approve $A_OPERATOR "$A" "$MP"; MA=$MP
# The any-of guard denies before the message is read, so its one audit row names the first listed permission (#265).
S=$(snap); cancel $A_VIEWER "$A" "$MA"; denied P3-PERM-05 $H "VIEWER cancel approved" MESSAGE_DRAFT "$S"

echo "== P3-PERM-06 send: allowed roles; VIEWER denied with no attempt row and no provider call"
stub_set send accept
for u in $A_OWNER $A_ADMIN $A_OPERATOR; do
  read -r cid pid m <<< "$(ready_approved $A_OPERATOR "$A" "PERM06 $u $RUN")"; n0=$(stub_calls send); send $u "$A" "$m"
  chk P3-PERM-06 $H "$u send" "201 1" "$CODE $(stub_since send $n0)"
done
S=$(snap); send $A_VIEWER "$A" "$MA"; denied P3-PERM-06 $H "VIEWER send" MESSAGE_SEND "$S"
chk P3-PERM-06 $H "VIEWER send: message still APPROVED, no attempt" "APPROVED 0" "$(msg_status "$MA" | cut -d/ -f1) $(attempts_n "$MA")"

echo "== P3-PERM-07 resolutions route: OWNER and ADMIN allowed, OPERATOR and VIEWER denied"
read -r c p MU <<< "$(ready_unknown $A_OPERATOR "$A" "PERM07 $RUN")"; U0=$(msg_status "$MU")
for u in $A_OPERATOR $A_VIEWER; do S=$(snap); resolve $u "$A" "$MU" FAILED; denied P3-PERM-07 $H "$u resolution" INTEGRATION_MANAGE "$S"; done
chk P3-PERM-07 $H "after both denials: message unchanged (SENDING, unknown), no resolution row" "$U0 0" "$(msg_status "$MU") $(resolutions_n "$MU")"
for u in $A_OWNER $A_ADMIN; do
  [ "$u" = "$A_ADMIN" ] && read -r c p MU <<< "$(ready_unknown $A_OPERATOR "$A" "PERM07 admin $RUN")"
  n0=$(stub_calls send); resolve $u "$A" "$MU" FAILED
  chk P3-PERM-07 $H "$u resolution FAILED: 201, message FAILED, one resolution row, no provider call" "201 FAILED 1 0" "$CODE $(echo "$BODY" | jq -r .status) $(resolutions_n "$MU") $(stub_since send $n0)"
done
S=$(snap); resolve $A_OWNER "$A" "$MA" FAILED; refused P3-PERM-07 $H "OWNER resolution of an APPROVED message (outcome known)" INVALID_TRANSITION 409 "$S"

echo "== P3-PERM-08 template reads"
for u in $A_OWNER $A_ADMIN $A_OPERATOR $A_VIEWER; do req $u "$A" GET /api/templates -; chk P3-PERM-08 $H "$u GET /api/templates" 200 "$CODE"; done

echo "== P3-PERM-09 template writes on an unused intent (SEASONAL_EVENT)"
tw() { local v; v=$(getj $A_OWNER "$A" /api/templates | jq -r '.items[]|select(.templateIntent=="SEASONAL_EVENT")|.version'); local body='{"providerTemplateName":"qa149_seasonal","language":"es","parameterNames":["customer_name"],"enabled":true}'
  if [ -n "$v" ] && [ "$v" != null ]; then req $1 "$A" PUT /api/templates/SEASONAL_EVENT "$body" -H "If-Match: \"$v\""; else req $1 "$A" PUT /api/templates/SEASONAL_EVENT "$body" -H 'If-None-Match: *'; fi; }
for u in $A_OWNER $A_ADMIN $A_OPERATOR; do tw $u; chk P3-PERM-09 $H "$u PUT template" 200 "$CODE"; done
S=$(snap); tw $A_VIEWER; denied P3-PERM-09 $H "VIEWER PUT template" TEMPLATE_WRITE "$S"

echo "== P3-PERM-10 integration reads"
for u in $A_OWNER $A_ADMIN $A_OPERATOR $A_VIEWER; do req $u "$A" GET /api/integrations/whatsapp -; chk P3-PERM-10 $H "$u GET integration" 200 "$CODE"; done

echo "== P3-PERM-11 integration writes: OWNER, ADMIN allow; OPERATOR, VIEWER denied"
iw() { req $1 "$A" GET /api/integrations/whatsapp -; req $1 "$A" PUT /api/integrations/whatsapp "$(jq -nc --arg p "$PN_A" --arg w "$WABA_A" '{phoneNumberId:$p,businessAccountId:$w,displayPhoneNumber:"+50600000000",enabled:true,secretRef:"qa149-token-a"}')" -H "If-Match: $(etag)"; }
for u in $A_OWNER $A_ADMIN; do iw $u; chk P3-PERM-11 $H "$u PUT integration" 200 "$CODE"; done
for u in $A_OPERATOR $A_VIEWER; do S=$(snap); iw $u; denied P3-PERM-11 $H "$u PUT integration" INTEGRATION_MANAGE "$S"; done
restore_integration $A_OWNER "$A" "$PN_A" "$WABA_A" qa149-token-a

echo "== P3-PERM-12 tenant kill switch: OWNER, ADMIN allow; OPERATOR, VIEWER denied"
sw() { req $1 "$A" PUT /api/integrations/whatsapp/outbound "{\"enabled\":$2}" -H "If-Match: $(integ_etag $1 "$A")"; }
for u in $A_OWNER $A_ADMIN; do sw $u false; c1=$CODE; sw $u true; chk P3-PERM-12 $H "$u switch off then on" "200 200" "$c1 $CODE"; done
for u in $A_OPERATOR $A_VIEWER; do S=$(snap); sw $u false; denied P3-PERM-12 $H "$u switch off" INTEGRATION_MANAGE "$S"; done
chk P3-PERM-12 $H "switch still on after the denials" true "$(getj $A_OWNER "$A" /api/integrations/whatsapp | jq -r .outboundEnabled)"

echo "== P3-PERM-14 one OPERATOR submits, approves and sends (allowed by ADR 0023 §2)"
read -r cid pid <<< "$(mk_customer $A_OPERATOR "$A" "PERM14 $RUN")"; make_due $A_OPERATOR "$A" "$cid" "$pid"
submit $A_OPERATOR "$A" "$cid" "$pid"; c1=$CODE; approve $A_OPERATOR "$A" "$MSG"; c2=$CODE; send $A_OPERATOR "$A" "$MSG"
chk P3-PERM-14 $H "operator alone: submit, approve, send" "201 201 201" "$c1 $c2 $CODE"

echo "== P3-PERM-15 VIEWER approval straight from curl with a valid session and CSRF token"
read -r CA PA MP <<< "$(ready_pending $A_OPERATOR "$A" "PERM15 $RUN")"
S=$(snap); approve $A_VIEWER "$A" "$MP"; denied P3-PERM-15 $H "VIEWER approve via curl" MESSAGE_APPROVE "$S"

echo "== P3-PERM-16 VIEWER approval with no Idempotency-Key and no If-Match: 403 before validation"
S=$(snap); req $A_VIEWER "$A" POST "/api/messages/$MP/approvals" '{"decision":"APPROVE"}'; denied P3-PERM-16 $H "VIEWER approve without headers" MESSAGE_APPROVE "$S"
req $A_OPERATOR "$A" POST "/api/messages/$MP/approvals" '{"decision":"APPROVE"}'; chk P3-PERM-16 $H "control: OPERATOR without headers is the validation 400" "400 ok" "$CODE $(problem_ok INVALID_INPUT)"

echo "== P3-PERM-17 role read at execution: OPERATOR demoted to VIEWER mid session"
read -r CA PA MA <<< "$(ready_approved $A_OPERATOR "$A" "PERM17 $RUN")"
req $A_OWNER "$A" PUT "/api/memberships/$ID_A_OPERATOR/role" '{"role":"VIEWER"}'; chk P3-PERM-17 $H "owner demotes the operator" 204 "$CODE"
S=$(snap); send $A_OPERATOR "$A" "$MA"; denied P3-PERM-17 $H "demoted operator send in the old session" MESSAGE_SEND "$S"
chk P3-PERM-17 $H "no attempt row" 0 "$(attempts_n "$MA")"
req $A_OWNER "$A" PUT "/api/memberships/$ID_A_OPERATOR/role" '{"role":"OPERATOR"}'; echo "  restored operator role: $CODE"

echo "== P3-PERM-18 revoked membership, old session (throwaway identity)"
TMPU=qa149rev$RUN; kc_create_user $TMPU >/dev/null; qa_login $TMPU; TMPID=$(identity $TMPU)
req $A_OWNER "$A" POST /api/memberships "{\"identityId\":\"$TMPID\",\"role\":\"OPERATOR\"}"; c1=$CODE
read -r CA PA MA <<< "$(ready_approved $A_OPERATOR "$A" "PERM18 $RUN")"
send $TMPU "$A" "$MA" qa149-$RUN-probe "\"0\""; c2=$CODE  # stale on purpose: proves the session works before the revoke
req $A_OWNER "$A" DELETE "/api/memberships/$TMPID" -; c3=$CODE
chk P3-PERM-18 $H "member added, usable (409 stale), then revoked" "201 409 204" "$c1 $c2 $c3"
S=$(snap); send $TMPU "$A" "$MA"; chk P3-PERM-18 $H "revoked member send: 403" 403 "$CODE"
chk P3-PERM-18 $H "revoked member send: no attempt row, no provider call, nothing else written" "0 ok" "$(attempts_n "$MA") $(unchanged "$S" any)"

echo "== P3-PERM-19 signed in identity with no membership, X-Tenant-Id of tenant A"
for p in "GET /api/messages" "GET /api/messages/$MA" "POST /api/messages/$MA/approvals" "POST /api/messages/$MA/sends" "GET /api/templates" "GET /api/integrations/whatsapp" "PUT /api/integrations/whatsapp/outbound"; do
  set -- $p; S=$(snap); d='{"enabled":false}'; [ "$1" = GET ] && d=-
  req $X_NONE "$A" $1 $2 "$d" -H "Idempotency-Key: qa149-$RUN-$RANDOM" -H 'If-Match: "1"'
  chk P3-PERM-19 $H "X-NONE $1 $2: 403, nothing written, no tenant A data" "403 ok 0" "$CODE $(unchanged "$S" any) $(echo "$BODY" | grep -c "$A")"
done
summary
