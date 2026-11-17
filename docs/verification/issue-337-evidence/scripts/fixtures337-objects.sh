#!/usr/bin/env bash
# ABOUTME: Suite #337 object fixtures: tenant A customers for the role, XSS and IDOR probes, and a tenant B customer,
# ABOUTME: contact, template mapping and pending message (the foreign objects for SEC-02). Synthetic values only.
set -uo pipefail
. "$(dirname "$0")/qa337.sh"
C=SETUP
# Reruns skip what $OUTD/ids already holds.
[ -n "${RA:-}" ] || mkcust RA "QA337 Roles" || exit 1
[ -n "${IA:-}" ] || mkcust IA "QA337 Norte IDOR" || exit 1
# The XSS canary is inert text: an <img onerror> that would set a page flag only if the browser ever parsed it as HTML.
XSS='QA337-XSS <img src=x onerror="window.__qa337=1"> "><svg onload="window.__qa337=2"> {{7*7}}'
[ -n "${XA:-}" ] || mkcust XA "QA337 Canario $XSS" || exit 1
req testuser "$A" GET "/api/customers/$XA" -
req testuser "$A" PUT "/api/customers/$XA" "$(printf '%s' "$BODY" | jq -c --arg n "QA337 nota $XSS" '{displayName,notes:$n,phones:[.phones[]|{number:.e164,region:"CL",primary}]}')" -H "If-Match: $(etag)"
rec $C "XSS canary stored as text in the customer name and notes" "$( { [ "$CODE" = 200 ]; } && echo PASS || echo FAIL)" "http=$CODE code=$(code)"

# Tenant B: map and refresh GENERAL_FOLLOW_UP through the stub (a template read, never a send), then one customer
# with WhatsApp consent and one pending manual message.
req testuser "$B" GET /api/templates -
if [ "$(jb '.items[]|select(.templateIntent=="GENERAL_FOLLOW_UP")|.sendable')" != true ]; then
  req testuser "$B" PUT /api/templates/GENERAL_FOLLOW_UP '{"providerTemplateName":"qa333_general_follow_up","language":"es","parameterNames":["customer_name","message_body"],"enabled":true}' -H 'If-None-Match: *'
  echo "  B template map: $CODE $(code)"
  req testuser "$B" POST /api/templates/GENERAL_FOLLOW_UP/approval-refresh - -H "If-Match: $(etag)"
  echo "  B template refresh: $CODE $(jb .sendable)"
fi
if [ -z "${IB:-}" ]; then
PH=$(next_phone)
req testuser "$B" POST /api/customers "$(jq -cn --arg p "$PH" '{displayName:"QA337 Sur IDOR",notes:"QA337 sur nota",phones:[{number:$p,region:"CL",primary:true}]}')"
[ "$CODE" = 201 ] || { echo "B customer: $CODE $(code)" >&2; exit 1; }
setid IB "$(jb .id)"; setid IB_C "$(jb '.phones[0].id')"
fi
req testuser "$B" GET "/api/customers/$IB/contact-policy" -
req testuser "$B" PUT "/api/customers/$IB/contacts/$IB_C/consents/WHATSAPP" '{"status":"GRANTED","source":"CUSTOMER_VERBAL"}' -H "If-Match: $(etag)"
req testuser "$B" POST "/api/customers/$IB/purchases" "$(jq -cn --arg t "$(date -u -d '-40 days' +%FT%TZ)" '{purchasedAt:$t,description:"Compra sintetica QA337 sur"}')" -H "Idempotency-Key: qa337-p-$IB"
req testuser "$B" GET "/api/customers/$IB/follow-up-policy" -; TZB=$(jb '.timeZone // "UTC"')
req testuser "$B" PUT "/api/customers/$IB/follow-up-policy" "$(jq -cn --arg d "$(TZ=$TZB date +%F)" '{cadenceDays:null,explicitNextDate:$d,snoozedUntil:null}')" -H "If-Match: $(etag)"
req testuser "$B" POST "/api/customers/$IB/messages" '{"contactId":"'"$IB_C"'","action":"GENERAL_CHECK_IN","templateIntent":"GENERAL_FOLLOW_UP","body":"QA337 mensaje sur sintetico","locale":"es-419","origin":"MANUAL"}' -H "If-Match: $(etag)" -H "Idempotency-Key: qa337-b-submit-$IB"
[ "$CODE" = 201 ] || { echo "B submit: $CODE $(code)" >&2; exit 1; }
setid MB "$(jb .messageId)"
rec $C "tenant B foreign objects: customer, contact, pending message" "$( { [ "$(jb .status)" = PENDING_APPROVAL ]; } && echo PASS || echo FAIL)" "customer=${IB:0:8} message=${MB:0:8} status=$(jb .status)"
