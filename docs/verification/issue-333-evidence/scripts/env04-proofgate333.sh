#!/usr/bin/env bash
# ABOUTME: P3-MQA-ENV-04 isolated proof gate (user-approved controlled deviation): global outbound ON only against the
# ABOUTME: loopback Graph stub with synthetic tokens; unproven and disabled integrations must refuse with zero stub sends.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa333.sh"
. "$OUTD/ids"
STUBDIR=$QA_HOME/stub; C=ENV-04
cd "$WT" || exit 2
BASEURL=$(docker compose exec -T backend sh -c 'printf %s "$DOKENE_INTEGRATION_WHATSAPP_GRAPH_BASE_URL"')
GLOBAL=$(docker compose exec -T backend sh -c 'printf %s "$DOKENE_MESSAGING_OUTBOUND_ENABLED"')
[ "$BASEURL" = http://127.0.0.1:18080 ] && [ "$GLOBAL" = true ] || { echo "precondition: base=$BASEURL global=$GLOBAL; refusing" >&2; exit 2; }
rec $C "[proof-gate] precondition: Graph base URL is the loopback stub, global switch ON" PASS "base=loopback-stub global=$GLOBAL"
sends() { grep -c 'kind=send' "$STUBDIR/requests.log" 2>/dev/null; true; }
S0=$(sends)
for u in testuser testoperator; do qa_login $u; done
integ() { req testuser "$A" GET /api/integrations/whatsapp -; }
integ; CFG=$(printf '%s' "$BODY" | jq -c '{phoneNumberId:"100000000000003",businessAccountId,displayPhoneNumber,enabled:true,secretRef}')
req testuser "$A" PUT /api/integrations/whatsapp "$CFG" -H "If-Match: $(etag)"
req testuser "$A" PUT /api/integrations/whatsapp/outbound '{"enabled":true}' -H "If-Match: $(etag)"
rec $C "[proof-gate] integration unproven, enabled, tenant switch ON" $([ "$(printf '%s' "$BODY" | jq -r '"\(.webhookVerified)/\(.enabled)/\(.outboundEnabled)"')" = false/true/true ] && echo PASS || echo FAIL) "$(printf '%s' "$BODY" | jq -c '{webhookVerified,enabled,outboundEnabled}')"
req testoperator "$A" GET /api/customers -; ANA=$(printf '%s' "$BODY" | jq -r '.customers[]|select(.displayName=="QA333 Ana Vencida").id')
req testoperator "$A" GET "/api/customers/$ANA" -; CONTACT=$(printf '%s' "$BODY" | jq -r '.phones[0].id')
req testoperator "$A" GET "/api/customers/$ANA/follow-up-policy" -; PV=$(etag)
SUB=$(jq -nc --arg c "$CONTACT" '{contactId:$c,action:"GENERAL_CHECK_IN",templateIntent:"GENERAL_FOLLOW_UP",body:"Hola Ana, ¿cómo te ha ido con tu compra?",locale:"es-419",origin:"MANUAL"}')
req testoperator "$A" POST "/api/customers/$ANA/messages" "$SUB" -H "If-Match: $PV" -H "Idempotency-Key: qa333-pg-$(date +%s)"
MID=$(printf '%s' "$BODY" | jq -r '.messageId // .id // empty'); [ -n "$MID" ] || { rec $C "[proof-gate] submit failed" FAIL "http=$CODE"; exit 1; }
req testoperator "$A" POST "/api/messages/$MID/approvals" '{"decision":"APPROVE"}' -H "If-Match: $(etag)" -H "Idempotency-Key: qa333-pg-ap-$MID"
rec $C "[proof-gate] message submitted and approved" NOTE "status=$(printf '%s' "$BODY" | jq -r .status)"
req testoperator "$A" POST "/api/messages/$MID/sends" - -H "If-Match: $(etag)" -H "Idempotency-Key: qa333-pg-s1-$MID"
rec $C "[proof-gate] UNPROVEN integration cannot send" $([ "$(printf '%s' "$BODY" | jq -r .code)" = INTEGRATION_DISABLED ] && echo PASS || echo FAIL) "http=$CODE code=$(printf '%s' "$BODY" | jq -r '.code // .status')"
# proven but integration disabled
integ; CFG=$(printf '%s' "$BODY" | jq -c '{phoneNumberId:"100000000000001",businessAccountId,displayPhoneNumber,enabled:true,secretRef}')
req testuser "$A" PUT /api/integrations/whatsapp "$CFG" -H "If-Match: $(etag)"
req testuser "$A" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: $(etag)"
CFG=$(printf '%s' "$BODY" | jq -c '{phoneNumberId,businessAccountId,displayPhoneNumber,enabled:false,secretRef}')
req testuser "$A" PUT /api/integrations/whatsapp "$CFG" -H "If-Match: $(etag)"
rec $C "[proof-gate] disabling the integration clears the proof" $([ "$(printf '%s' "$BODY" | jq -r '"\(.enabled)/\(.webhookVerified)"')" = false/false ] && echo PASS || echo FAIL) "$(printf '%s' "$BODY" | jq -c '{enabled,webhookVerified,outboundEnabled}')"
req testoperator "$A" GET "/api/messages/$MID" -; MST=$(printf '%s' "$BODY" | jq -r .status)
req testoperator "$A" POST "/api/messages/$MID/sends" - -H "If-Match: $(etag)" -H "Idempotency-Key: qa333-pg-s2-$MID"
rec $C "[proof-gate] DISABLED integration cannot send" $([ "$(printf '%s' "$BODY" | jq -r '.code // empty')" = INTEGRATION_DISABLED ] || [ "${CODE:0:1}" = 4 ] && echo PASS || echo FAIL) "pre=$MST http=$CODE code=$(printf '%s' "$BODY" | jq -r '.code // .status')"
# cleanup: cancel message, tenant switch off, restore enabled + proven
req testoperator "$A" GET "/api/messages/$MID" -
[ "$(printf '%s' "$BODY" | jq -r .status)" = APPROVED ] && req testoperator "$A" POST "/api/messages/$MID/cancellations" '{}' -H "If-Match: $(etag)" -H "Idempotency-Key: qa333-pg-c-$MID"
rec $C "[proof-gate] cleanup: probe message" NOTE "http=$CODE status=$(printf '%s' "$BODY" | jq -r '.status // .code')"
integ; req testuser "$A" PUT /api/integrations/whatsapp/outbound '{"enabled":false}' -H "If-Match: $(etag)"
CFG=$(printf '%s' "$BODY" | jq -c '{phoneNumberId,businessAccountId,displayPhoneNumber,enabled:true,secretRef}')
req testuser "$A" PUT /api/integrations/whatsapp "$CFG" -H "If-Match: $(etag)"
req testuser "$A" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: $(etag)"
rec $C "[proof-gate] cleanup: enabled+proven, tenant switch OFF" $([ "$(printf '%s' "$BODY" | jq -r '"\(.enabled)/\(.webhookVerified)/\(.outboundEnabled)"')" = true/true/false ] && echo PASS || echo FAIL) "$(printf '%s' "$BODY" | jq -c '{enabled,webhookVerified,outboundEnabled}')"
S1=$(sends); rec $C "[proof-gate] zero provider send calls" $([ "$S1" = "$S0" ] && echo PASS || echo FAIL) "stub sends before=$S0 after=$S1"
