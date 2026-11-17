#!/usr/bin/env bash
# ABOUTME: P3-MQA-SEC-02: cross-tenant object substitution over direct HTTP. The same person (testuser: OWNER of A,
# ABOUTME: ADMIN of B) swaps message, customer, contact, integration and template references across tenants.
set -uo pipefail
. "$(dirname "$0")/qa337.sh"
C=SEC-02
assert_safe_stack false || exit 1
for u in testuser testoperator testoperator2; do qa_login "$u" || exit 1; done
# A pending message in tenant A (the foreign object seen from B).
if [ -z "${MA:-}" ]; then
  req testuser "$A" GET "/api/customers/$IA/follow-up-policy" -
  req testuser "$A" POST "/api/customers/$IA/messages" "{\"contactId\":\"$IA_C\",\"action\":\"GENERAL_CHECK_IN\",\"templateIntent\":\"GENERAL_FOLLOW_UP\",\"body\":\"QA337 mensaje norte sintetico\",\"locale\":\"es-419\",\"origin\":\"MANUAL\"}" -H "If-Match: $(etag)" -H "Idempotency-Key: $(uuidgen)"
  [ "$CODE" = 201 ] || { echo "A submit: $CODE $(code)" >&2; exit 1; }
  setid MA "$(jb .messageId)"
fi
req testuser "$A" GET "/api/messages/$MA" -; EA=$(etag); RA_REV=$(jb .template.revision)
req testuser "$B" GET "/api/messages/$MB" -; EB=$(etag); RB_REV=$(jb .template.revision)
IA_PHONE=$(psqlq "select normalized_phone from dokene.customer_phone_contacts where id='$IA_C'")

# idor <label> <want-http> <user> <tenant> <METHOD> <path> <json|-> [curl args]: refused with <want-http>, the body names
# no foreign id or name, and no state, stub or foreign message row changes.
idor() {
  local label=$1 want=$2 u=$3 t=$4 m=$5 p=$6 d=$7; shift 7
  local s0 a0 b0 st0 leak
  s0=$(snap1); a0=$(mrowv "$MA"); b0=$(mrowv "$MB"); st0=$(stub_all)
  req "$u" "$t" "$m" "$p" "$d" "$@"
  leak=n; printf '%s' "$BODY" | grep -qE "$MA|$MB|$IA|$IB|$IA_C|$IB_C|QA337 Norte|QA337 Sur|${IA_PHONE//+/\\+}" && leak=y
  rec $C "$label: $want, no foreign data in the body, nothing written" \
    "$( { [ "$CODE" = "$want" ] && [ $leak = n ] && [ "$(snap1)" = "$s0" ] && [ "$(mrowv "$MA")" = "$a0" ] && [ "$(mrowv "$MB")" = "$b0" ] && [ "$(stub_all)" = "$st0" ]; } && echo PASS || echo FAIL)" \
    "http=$CODE code=$(code) leak=$leak state_same=$([ "$(snap1)" = "$s0" ] && echo y || echo n)"
}
KEY() { printf 'Idempotency-Key: %s' "$(uuidgen)"; }
# --- B context, A objects (message)
idor "B ctx: read A message" 404 testuser "$B" GET "/api/messages/$MA" -
idor "B ctx: read A message events" 404 testuser "$B" GET "/api/messages/$MA/events" -
idor "B ctx: approve A message (A's ETag and revision)" 404 testuser "$B" POST "/api/messages/$MA/approvals" "{\"decision\":\"APPROVE\",\"templateRevision\":\"$RA_REV\"}" -H "If-Match: $EA" -H "$(KEY)"
idor "B ctx: cancel A message" 404 testuser "$B" POST "/api/messages/$MA/cancellations" '{"note":"QA337 idor"}' -H "If-Match: $EA" -H "$(KEY)"
idor "B ctx: send A message" 404 testuser "$B" POST "/api/messages/$MA/sends" - -H "If-Match: $EA" -H "$(KEY)"
idor "B ctx: resolve A message" 404 testuser "$B" POST "/api/messages/$MA/resolutions" '{"outcome":"FAILED","note":"QA337"}' -H "If-Match: $EA" -H "$(KEY)"
# --- A context, B objects (reverse direction)
idor "A ctx: read B message" 404 testuser "$A" GET "/api/messages/$MB" -
idor "A ctx: approve B message (B's ETag and revision)" 404 testuser "$A" POST "/api/messages/$MB/approvals" "{\"decision\":\"APPROVE\",\"templateRevision\":\"$RB_REV\"}" -H "If-Match: $EB" -H "$(KEY)"
# --- customer and contact
req testuser "$A" GET "/api/customers/$IA" -; EC=$(etag)
idor "B ctx: read A customer" 404 testuser "$B" GET "/api/customers/$IA" -
idor "B ctx: update A customer" 404 testuser "$B" PUT "/api/customers/$IA" '{"displayName":"QA337 hijack","notes":null,"phones":[]}' -H "If-Match: $EC"
idor "B ctx: A customer purchases" 404 testuser "$B" GET "/api/customers/$IA/purchases" -
idor "B ctx: A customer contact policy" 404 testuser "$B" GET "/api/customers/$IA/contact-policy" -
idor "B ctx: revoke consent on A contact" 404 testuser "$B" PUT "/api/customers/$IA/contacts/$IA_C/consents/WHATSAPP" '{"status":"REVOKED","source":"CUSTOMER_VERBAL"}' -H 'If-Match: "0"'
idor "B ctx: A customer follow-up policy" 404 testuser "$B" GET "/api/customers/$IA/follow-up-policy" -
idor "B ctx: AI draft for A customer" 404 testuser "$B" POST "/api/customers/$IA/draft" '{}'
idor "B ctx: submit on A customer" 404 testuser "$B" POST "/api/customers/$IA/messages" "{\"contactId\":\"$IA_C\",\"action\":\"GENERAL_CHECK_IN\",\"templateIntent\":\"GENERAL_FOLLOW_UP\",\"body\":\"QA337 idor\",\"locale\":\"es-419\",\"origin\":\"MANUAL\"}" -H 'If-Match: "0"' -H "$(KEY)"
# B's own customer with A's contact id: the body reference must not resolve across tenants either.
req testuser "$B" GET "/api/customers/$IB/follow-up-policy" -; PB=$(etag)
# MB is still open on IB, so a valid contact would be refused as MESSAGE_ALREADY_OPEN: record the code to tell them apart.
idor "B ctx: submit on B customer with A's contact id" "${MIX_HTTP:-404}" testuser "$B" POST "/api/customers/$IB/messages" "{\"contactId\":\"$IA_C\",\"action\":\"GENERAL_CHECK_IN\",\"templateIntent\":\"GENERAL_FOLLOW_UP\",\"body\":\"QA337 idor\",\"locale\":\"es-419\",\"origin\":\"MANUAL\"}" -H "If-Match: $PB" -H "$(KEY)"
# --- searches: see sec02b-337.sh (the first version read the wrong JSON path)
req testuser "$B" GET "/api/messages?customerId=$IA" -
rec $C "B ctx: message list filtered by A customer is empty" "$( { [ "$CODE" = 200 ] && [ "$(jb '.items|length')" = 0 ]; } && echo PASS || echo FAIL)" "http=$CODE rows=$(jb '.items|length')"
req testuser "$B" GET "/api/messages?limit=100" -
n=$(psqlq "select count(*) from dokene.outbound_messages where tenant_id='$A' and id::text = any(string_to_array('$(jb '[.items[].messageId]|join(",")')', ','))")
rec $C "B ctx: message list holds only tenant B messages" "$( { [ "$CODE" = 200 ] && [ "$n" = 0 ] && [ "$(jb '.items|length')" -ge 1 ]; } && echo PASS || echo FAIL)" "http=$CODE rows=$(jb '.items|length') a_rows=$n"
req testuser "$B" GET "/api/follow-up-queue" -
rec $C "B ctx: follow-up queue holds no tenant A customer" "$( { [ "$CODE" = 200 ] && ! printf '%s' "$BODY" | grep -qE "$IA|$RA|$XA|QA337 Norte|QA337 Roles"; } && echo PASS || echo FAIL)" "http=$CODE"
# --- membership header switching: an A-only member naming tenant B gets no context
idor "A-only OPERATOR with tenant B header reads B message" 403 testoperator "$B" GET "/api/messages/$MB" -
idor "B-only OPERATOR with tenant A header reads A message" 403 testoperator2 "$A" GET "/api/messages/$MA" -
# --- templates: per tenant and intent, no shared object. B's mapping is its own.
req testuser "$A" GET /api/templates -; TA=$(jb '.items[]|select(.templateIntent=="GENERAL_FOLLOW_UP")|.version')
req testuser "$B" GET /api/templates -; TBV=$(jb '.items[]|select(.templateIntent=="GENERAL_FOLLOW_UP")|.version')
idor "B ctx: template write with A's template version as If-Match" 409 testuser "$B" PUT /api/templates/GENERAL_FOLLOW_UP '{"providerTemplateName":"qa337_hijack","language":"es","parameterNames":["customer_name","message_body"],"enabled":true}' -H "If-Match: \"$TA\""
rec $C "template versions are per tenant (A and B differ; the A version was refused above)" "$( { [ "$TA" != "$TBV" ]; } && echo PASS || echo FAIL)" "a_version=$TA b_version=$TBV"
# --- integration substitution: see sec02b-337.sh (ADR 0024 #210: PUT accepts any unverified claim)
# --- audit: denials and refusals of these probes recorded none of A's object ids under tenant B
n=$(psqlq "select count(*) from dokene.audit_events where tenant_id='$B' and (target_id::text in ('$MA','$IA','$IA_C'))")
rec $C "tenant B audit rows never reference tenant A objects" "$( { [ "$n" = 0 ]; } && echo PASS || echo FAIL)" "rows=$n"
# Recorded FAIL lines make the exit status nonzero.
qa_finish
