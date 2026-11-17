#!/usr/bin/env bash
# ABOUTME: Suite #335 window W1 (global ON, loopback stub): SEND-01 tenant OFF, SEND-02 contact and customer changes
# ABOUTME: after approval, SEND-03 authorizing role change. Each refusal: distinct code, 0 attempts, 0 provider requests.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa335.sh"
assert_safe_stack true || exit 2
stub_reset
for u in testuser testoperator; do qa_login $u || exit 3; done

C=SEND-01
integ; req testuser "$A" PUT /api/integrations/whatsapp/outbound '{"enabled":false}' -H "If-Match: $(etag)"
rec $C "W1: tenant A outbound OFF while global ON (isolated stub stack)" "$(ok200)" "http=$CODE tenant=$(tenant_state)"
S0=$(stub_sends); send testuser "$M_S1"; refused OUTBOUND_DISABLED_FOR_TENANT "$M_S1" "$S0" "global ON, tenant OFF, OWNER send (http=$CODE)"
integ; req testuser "$A" PUT /api/integrations/whatsapp/outbound '{"enabled":true}' -H "If-Match: $(etag)"
rec $C "tenant A outbound back ON for the rest of W1" "$(ok200)" "http=$CODE tenant=$(tenant_state)"

C=SEND-02
S0=$(stub_sends); CODES=""
# Consent revoked after approval.
req testuser "$A" GET "/api/customers/$S2CONS/contact-policy" -
req testuser "$A" PUT "/api/customers/$S2CONS/contacts/$S2CONS_C/consents/WHATSAPP" '{"status":"REVOKED","source":"CUSTOMER_VERBAL"}' -H "If-Match: $(etag)"
P=$CODE; send testuser "$M_S2CONS"; CODES="$CODES $(code)"; refused NO_CONTACT_CONSENT "$M_S2CONS" "$S0" "consent REVOKED after approval (policy http=$P, send http=$CODE)"
# Do-not-contact after approval.
req testuser "$A" GET "/api/customers/$S2DNC/contact-policy" -
req testuser "$A" PUT "/api/customers/$S2DNC/do-not-contact" '{"enabled":true,"source":"CUSTOMER_VERBAL"}' -H "If-Match: $(etag)"
P=$CODE; send testuser "$M_S2DNC"; CODES="$CODES $(code)"; refused DO_NOT_CONTACT "$M_S2DNC" "$S0" "do-not-contact set after approval (policy http=$P, send http=$CODE)"
# Customer archived after approval.
req testuser "$A" GET "/api/customers/$S2ARCH" -
req testuser "$A" DELETE "/api/customers/$S2ARCH" - -H "If-Match: $(etag)"
P=$CODE; send testuser "$M_S2ARCH"; CODES="$CODES $(code)"; refused CUSTOMER_ARCHIVED "$M_S2ARCH" "$S0" "customer archived after approval (archive http=$P, send http=$CODE)"
# Recipient contact replaced after approval (new number => the approved contact id no longer belongs to the customer).
NEWP=$(next_phone)
req testuser "$A" GET "/api/customers/$S2CONT" -
req testuser "$A" PUT "/api/customers/$S2CONT" "$(jq -nc --arg p "$NEWP" '{displayName:"QA335 Hugo Contacto",notes:null,phones:[{number:$p,region:"CL",primary:true}]}')" -H "If-Match: $(etag)"
P=$CODE; send testuser "$M_S2CONT"; CODES="$CODES $(code)"; refused CONTACT_NOT_OWNED "$M_S2CONT" "$S0" "recipient number replaced after approval (update http=$P, send http=$CODE)"
# Follow-up made ineligible after approval: w1-ineligible335.sh (snooze route and a future explicit date). The recorded
# run first tried it here with PUT follow-up-policy, which has no snooze field: its explicitNextDate:null made the
# customer due by cadence, so that message was legitimately sent (harness error, superseded; see the report).
U=$(printf '%s\n' $CODES | sort -u | wc -l)
rec $C "four contact/customer changes give four distinct refusal codes (FOLLOW_UP_INELIGIBLE: w1-ineligible335.sh)" "$([ "$U" = 4 ] && echo PASS || echo FAIL)" "codes=$(echo $CODES | tr ' ' ',')"
# Cross-tenant: the same message id addressed from tenant B (testuser is ADMIN there) reveals nothing.
req testuser "$B" POST "/api/messages/$M_S2CONS/sends" - -H 'If-Match: "1"' -H "Idempotency-Key: $(uuidgen)"
X=$CODE; XB=$(printf '%s' "$BODY" | jq -c '{code, keys: keys}' 2>/dev/null)
req testuser "$B" GET "/api/messages/$M_S2CONS" -
rec $C "tenant A message id sent or read from tenant B: 404 NOT_FOUND, no tenant A data" "$([ "$X" = 404 ] && [ "$CODE" = 404 ] && ! printf '%s' "$BODY$XB" | grep -q 'QA335\|5698335' && echo PASS || echo FAIL)" "send http=$X body=$XB get http=$CODE"
rec $C "refusal bodies carry only the error contract" NOTE "$(send testuser "$M_S2DNC"; printf '%s' "$BODY" | jq -c '{code, keys: keys}')"
rec $C "SEND-02 totals" "$([ "$(stub_sends)" = "$S0" ] && echo PASS || echo FAIL)" "stub_delta=$(( $(stub_sends) - S0 ))"

C=SEND-03
OP=$(identity testoperator)
S0=$(stub_sends)
req testuser "$A" PUT "/api/memberships/$OP/role" '{"role":"VIEWER"}'
rec $C "operator (who approved M_S3ROLE) demoted to VIEWER after approval" "$([ "$CODE" = 204 ] && echo PASS || echo FAIL)" "http=$CODE"
send testoperator "$M_S3ROLE"; H=$CODE; G=$(code)
rec $C "demoted member's send refused: 403, 0 attempts, 0 provider requests, still APPROVED" "$([ "$H" = 403 ] && [ "$(attempts "$M_S3ROLE")" = 0 ] && [ "$(stub_sends)" = "$S0" ] && [ "$(mstate "$M_S3ROLE")" = APPROVED/unknown=false/attempts=0 ] && echo PASS || echo FAIL)" "http=$H code=${G:-none} attempts=$(attempts "$M_S3ROLE") stub_delta=$(( $(stub_sends) - S0 ))"
req testuser "$A" PUT "/api/memberships/$OP/role" '{"role":"OPERATOR"}'
rec $C "operator role restored" "$([ "$CODE" = 204 ] && echo PASS || echo FAIL)" "http=$CODE"
send testoperator "$M_S3ROLE"
rec $C "control: same message, role restored -> authority re-read at dispatch, one send to the stub" "$([ "$CODE" = 201 ] && [ "$(jb .status)" = SENT ] && [ "$(stub_sends)" = $((S0 + 1)) ] && echo PASS || echo FAIL)" "http=$CODE status=$(jb .status) attempts=$(attempts "$M_S3ROLE") stub_delta=$(( $(stub_sends) - S0 ))"
qa_finish
