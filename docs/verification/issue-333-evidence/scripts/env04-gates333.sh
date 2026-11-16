#!/usr/bin/env bash
# ABOUTME: P3-MQA-ENV-04 gates: changing the number clears the ownership proof; an unproven integration cannot get a
# ABOUTME: message approved/sent; tenant switch on + global off still blocks. Counts stub sends (must stay 0).
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa333.sh"
. "$OUTD/ids"
C=ENV-04
for u in testuser testoperator; do qa_login $u; done
assert_safe_stack false || exit 2
S0=$(stub_sends) || S0=
integ() { req testuser "$A" GET /api/integrations/whatsapp -; }
show() { printf '%s' "$BODY" | jq -c '{enabled,outboundEnabled,webhookVerified,phoneNumberId,secretRef,version,code}' 2>/dev/null; }
integ; rec $C "baseline: proven, tenant switch off" $([ "$(printf '%s' "$BODY" | jq -r '"\(.webhookVerified)/\(.outboundEnabled)"')" = true/false ] && echo PASS || echo FAIL) "$(show)"
# 1. change the number -> proof cleared
CFG=$(printf '%s' "$BODY" | jq -c '{phoneNumberId:"100000000000003",businessAccountId,displayPhoneNumber,enabled,secretRef}')
req testuser "$A" PUT /api/integrations/whatsapp "$CFG" -H "If-Match: $(etag)"
rec $C "number change clears ownership proof" $([ "$(printf '%s' "$BODY" | jq -r .webhookVerified)" = false ] && echo PASS || echo FAIL) "http=$CODE $(show)"
req testuser "$A" GET /api/templates -; rec $C "template sendability with unproven integration" NOTE "GENERAL_FOLLOW_UP sendable=$(printf '%s' "$BODY" | jq -r '.items[]|select(.templateIntent=="GENERAL_FOLLOW_UP").sendable')"
# 2. unproven integration: submit / approve / send
req testoperator "$A" GET /api/customers -; ANA=$(printf '%s' "$BODY" | jq -r '.customers[]|select(.displayName=="QA333 Ana Vencida").id')
req testoperator "$A" GET "/api/customers/$ANA" -; CONTACT=$(printf '%s' "$BODY" | jq -r '.phones[0].id')
req testoperator "$A" GET "/api/customers/$ANA/follow-up-policy" -; PV=$(etag)
SUB=$(jq -nc --arg c "$CONTACT" '{contactId:$c,action:"GENERAL_CHECK_IN",templateIntent:"GENERAL_FOLLOW_UP",body:"Hola Ana, ¿cómo te ha ido con tu compra?",locale:"es-419",origin:"MANUAL"}')
req testoperator "$A" POST "/api/customers/$ANA/messages" "$SUB" -H "If-Match: $PV" -H "Idempotency-Key: qa333-env04-$(date +%s)"
MID=$(printf '%s' "$BODY" | jq -r '.id // .messageId // empty' 2>/dev/null); MV=$(etag)
rec $C "submit with unproven integration" NOTE "http=$CODE status=$(printf '%s' "$BODY" | jq -r '.status // .code' 2>/dev/null)"
if [ -n "$MID" ]; then
  req testoperator "$A" GET "/api/messages/$MID" -; MV=$(etag)
  req testoperator "$A" POST "/api/messages/$MID/approvals" '{"decision":"APPROVE"}' -H "If-Match: $MV" -H "Idempotency-Key: qa333-env04-ap-$MID"
  AP=$CODE; APC=$(printf '%s' "$BODY" | jq -r '.code // .status' 2>/dev/null)
  rec $C "approval with unproven integration" NOTE "http=$AP result=$APC"
  req testoperator "$A" GET "/api/messages/$MID" -; MV=$(etag)
  req testoperator "$A" POST "/api/messages/$MID/sends" - -H "If-Match: $MV" -H "Idempotency-Key: qa333-env04-send-$MID"
  rec $C "send with unproven integration is refused" $([ "${CODE:0:1}" = 4 ] || [ "${CODE:0:1}" = 5 ] && echo PASS || echo FAIL) "http=$CODE code=$(printf '%s' "$BODY" | jq -r '.code // .status' 2>/dev/null)"
fi
# 3. restore proof, turn ONLY the tenant switch on: global off must still block
integ; CFG=$(printf '%s' "$BODY" | jq -c '{phoneNumberId:"100000000000001",businessAccountId,displayPhoneNumber,enabled,secretRef}')
req testuser "$A" PUT /api/integrations/whatsapp "$CFG" -H "If-Match: $(etag)"
req testuser "$A" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: $(etag)"
rec $C "restore number and re-prove ownership" $([ "$(printf '%s' "$BODY" | jq -r .webhookVerified)" = true ] && echo PASS || echo FAIL) "http=$CODE $(show)"
req testuser "$A" PUT /api/integrations/whatsapp/outbound '{"enabled":true}' -H "If-Match: $(etag)"
rec $C "OWNER turns tenant switch on (global stays off)" $([ "$(printf '%s' "$BODY" | jq -r .outboundEnabled)" = true ] && echo PASS || echo FAIL) "http=$CODE"
if [ -n "${MID:-}" ]; then
  req testoperator "$A" GET "/api/messages/$MID" -; MST=$(printf '%s' "$BODY" | jq -r .status); MV=$(etag)
  if [ "$MST" = APPROVED ]; then
    req testoperator "$A" POST "/api/messages/$MID/sends" - -H "If-Match: $MV" -H "Idempotency-Key: qa333-env04-send2-$MID"
    rec $C "proven + tenant on + global off -> refused OUTBOUND_DISABLED_GLOBALLY" $([ "$(printf '%s' "$BODY" | jq -r .code)" = OUTBOUND_DISABLED_GLOBALLY ] && echo PASS || echo FAIL) "http=$CODE code=$(printf '%s' "$BODY" | jq -r .code)"
  else rec $C "message not APPROVED, global-off send probe skipped" NOTE "status=$MST"; fi
  req testoperator "$A" GET "/api/messages/$MID" -; MV=$(etag)
  req testoperator "$A" POST "/api/messages/$MID/cancellations" '{}' -H "If-Match: $MV" -H "Idempotency-Key: qa333-env04-cancel-$MID"
  rec $C "cleanup: cancel probe message" NOTE "http=$CODE status=$(printf '%s' "$BODY" | jq -r '.status // .code' 2>/dev/null)"
fi
integ; req testuser "$A" PUT /api/integrations/whatsapp/outbound '{"enabled":false}' -H "If-Match: $(etag)"
rec $C "OWNER turns tenant switch back off" $([ "$(printf '%s' "$BODY" | jq -r .outboundEnabled)" = false ] && echo PASS || echo FAIL) "http=$CODE"
S1=$(stub_sends) || S1=; rec $C "zero provider send calls during ENV-04" $([ -n "$S0" ] && [ "$S1" = "$S0" ] && echo PASS || echo FAIL) "stub sends before=$S0 after=$S1"
c=$(psqlq "select count(*) from dokene.outbound_send_attempts" 2>/dev/null || echo n/a); rec $C "send attempt rows" NOTE "count=$c"
qa_finish
