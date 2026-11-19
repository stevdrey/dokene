#!/usr/bin/env bash
# ABOUTME: Suite #340 REG-01, API half: session/tenant switch, customer + phone rules, consent/DNC, purchases and last
# ABOUTME: purchase, due/overdue queue, snooze, manual completion/dismissal and archive transitions on the real stack.
# Re-runnable on a non-clean database: CL test-range phones come from next_phone and idempotency keys are per run.
set -uo pipefail
. "$(dirname "$0")/qa340.sh"
C=REG-01
RUN=$(date +%s)
for u in testuser testadmin testoperator testviewer testoperator2 testnone; do qa_login "$u" || { echo "login $u failed" >&2; exit 1; }; done
S0=$(stub_sends)

# --- login and tenant switch -------------------------------------------------------------------------------------
req - - GET /api/session -; chk $C "no session -> 401" 401 "$CODE"
req testuser - GET /api/tenants -
chk $C "OWNER testuser sees both workspaces (A OWNER, B ADMIN)" "OWNER ADMIN" \
  "$(printf '%s' "$BODY" | jq -r --arg a "$A" --arg b "$B" '[(.[]|select(.tenantId==$a).role),(.[]|select(.tenantId==$b).role)]|join(" ")')"
req testoperator2 - GET /api/tenants -; chk $C "testoperator2 sees only workspace B" "1 OPERATOR" "$(printf '%s' "$BODY" | jq -r --arg b "$B" '[length, (.[]|select(.tenantId==$b).role)]|join(" ")')"
req testoperator "$B" GET /api/customers -; chk $C "operator of A with workspace B header -> 403" 403 "$CODE"
req testnone - GET /api/tenants -; chk $C "identity without membership lists no workspace" 0 "$(printf '%s' "$BODY" | jq 'length')"

# --- customers and phones ----------------------------------------------------------------------------------------
P1=$(next_phone)
req testoperator "$A" POST /api/customers "$(jq -cn --arg p "$P1" '{displayName:"QA340 Ana Regresión",notes:"Cliente sintético",phones:[{number:$p,region:"CL",primary:true}]}')"
chk $C "OPERATOR creates CL customer" 201 "$CODE"; CA=$(jb .id); CA_P=$(jb '.phones[0].id'); setid REG01_CA "$CA"
req testoperator "$A" POST /api/customers "$(jq -cn --arg p "$P1" '{displayName:"QA340 Duplicado",phones:[{number:$p,region:"CL",primary:true}]}')"
chk $C "duplicate phone in the same workspace -> 409" 409 "$CODE"
req testoperator "$A" POST /api/customers '{"displayName":"QA340 Malo","phones":[{"number":"123","region":"CL","primary":true}]}'
chk $C "unparseable phone -> 400 naming phones[0].number (#72)" "400 phones[0].number" "$CODE $(jb .field)"
req testoperator "$A" POST /api/customers '{"displayName":"   ","phones":[{"number":"+56931110002","region":"CL","primary":true}]}'
chk $C "blank name -> 400 naming displayName" "400 displayName" "$CODE $(jb .field)"
req testoperator "$A" POST /api/customers "$(jq -cn --arg p "$(next_phone)" '{displayName:"QA340 Sin región",phones:[{number:($p|ltrimstr("+56")),primary:true}]}')"
chk $C "missing region -> 400 naming phones[0].region" "400 phones[0].region" "$CODE $(jb .field)"
req testviewer "$A" POST /api/customers "$(jq -cn --arg p "$(next_phone)" '{displayName:"QA340 Viewer",phones:[{number:$p,region:"CL",primary:true}]}')"
chk $C "VIEWER cannot create a customer" 403 "$CODE"
req testoperator "$A" GET "/api/customers/$CA" -; V=$(etag)
req testoperator "$A" PUT "/api/customers/$CA" "$(jq -cn --arg p "$P1" '{displayName:"QA340 Ana Regresión M",notes:"Cliente sintético",phones:[{number:$p,region:"CL",primary:true}]}')"
chk $C "update without If-Match -> 400 naming version" "400 version" "$CODE $(jb .field)"
req testoperator "$A" PUT "/api/customers/$CA" "$(jq -cn --arg p "$P1" '{displayName:"QA340 Ana Regresión M",notes:"Cliente sintético",phones:[{number:$p,region:"CL",primary:true}]}')" -H "If-Match: $V"
chk $C "update with the current version" 200 "$CODE"
req testoperator "$A" PUT "/api/customers/$CA" "$(jq -cn --arg p "$P1" '{displayName:"QA340 Stale",phones:[{number:$p,region:"CL",primary:true}]}')" -H "If-Match: $V"
chk $C "stale If-Match -> 409" 409 "$CODE"
req testoperator "$A" GET "/api/customers?name=QA340%20Ana" -; chk $C "search by name finds the customer" "QA340 Ana Regresión M" "$(jb '.customers[0].displayName')"
req testoperator "$A" GET "/api/customers?phone=${P1#+56}&region=CL" -; chk $C "search by national phone + region" "$CA" "$(jb '.customers[0].id')"
req testuser "$B" POST /api/customers "$(jq -cn --arg p "$P1" '{displayName:"QA340 Ana Sur",phones:[{number:$p,region:"CL",primary:true}]}')"
chk $C "same phone allowed in workspace B" 201 "$CODE"; CB=$(jb .id); setid REG01_CB "$CB"
req testuser "$B" GET "/api/customers?name=QA340%20Ana" -; BIDS=$(jb '.customers[].id' | sed "s/.*/'&'/" | paste -sd,)
chk $C "workspace B search returns B customers only (incl. the new one, never A's)" "ok" \
  "$( { [ "$(psqlq "select count(*) from dokene.customers where id in ($BIDS) and tenant_id<>'$B'")" = 0 ] && printf '%s' "$BIDS" | grep -q "$CB" && ! printf '%s' "$BIDS" | grep -q "$CA"; } && echo ok || echo "ids=$BIDS")"

# --- consent and do-not-contact ----------------------------------------------------------------------------------
el() { req testoperator "$A" GET "/api/customers/$CA/contact-eligibility?channel=WHATSAPP&contactId=$1" -; jb '[.eligible, (.reasons|join(","))]|@json'; }
pol() { req testoperator "$A" GET "/api/customers/$CA/contact-policy" -; etag; }
chk $C "new contact: consent UNKNOWN, not eligible" '[false,"CONSENT_UNKNOWN"]' "$(el "$CA_P")"
req testoperator "$A" PUT "/api/customers/$CA/contacts/$CA_P/consents/WHATSAPP" '{"status":"GRANTED","source":"CUSTOMER_VERBAL"}' -H "If-Match: $(pol)"
chk $C "grant WhatsApp consent" 200 "$CODE"; chk $C "granted -> eligible" '[true,""]' "$(el "$CA_P")"
req testoperator "$A" PUT "/api/customers/$CA/do-not-contact" '{"enabled":true,"source":"CUSTOMER_WRITTEN"}' -H "If-Match: $(pol)"
chk $C "do-not-contact on" 200 "$CODE"; chk $C "DNC overrides granted consent" '[false,"DO_NOT_CONTACT"]' "$(el "$CA_P")"
req testviewer "$A" PUT "/api/customers/$CA/do-not-contact" '{"enabled":false,"source":"OPERATOR_CORRECTION"}' -H "If-Match: $(pol)"
chk $C "VIEWER cannot change the contact policy" 403 "$CODE"
STALE=$(pol)
req testoperator "$A" PUT "/api/customers/$CA/do-not-contact" '{"enabled":false,"source":"OPERATOR_CORRECTION"}' -H "If-Match: $STALE"
chk $C "clear DNC" 200 "$CODE"; chk $C "eligible again after DNC cleared" '[true,""]' "$(el "$CA_P")"
req testoperator "$A" PUT "/api/customers/$CA/do-not-contact" '{"enabled":true,"source":"OPERATOR_CORRECTION"}' -H "If-Match: $STALE"
chk $C "stale contact-policy If-Match -> 409" 409 "$CODE"
req testoperator "$A" GET "/api/customers/$CA/contact-policy/history" -
chk $C "policy history lists consent and DNC changes" ok "$([ "$(jb '(.events // .items // .history // [])|length')" -ge 3 ] && echo ok || echo "n=$(jb '(.events // .items // .history // [])|length')")"

# --- purchases -----------------------------------------------------------------------------------------------------
PU=/api/customers/$CA/purchases
mkp() { req testoperator "$A" POST "$PU" "$(jq -cn --arg t "$1" --arg d "$2" '{purchasedAt:$t,description:$d}')" -H "Idempotency-Key: qa340-$RUN-$3"; }
d() { date -u -d "$1" +%FT15:00:00Z; }
mkp "$(d '-70 days')" "Café 1 kg" a; chk $C "record a purchase" 201 "$CODE"; PA=$(jb .id)
mkp "$(d '-45 days')" "Café 500 g" b; PB=$(jb .id)
mkp "$(d '-120 days')" "Compra retroactiva" c; chk $C "record a backdated purchase" 201 "$CODE"
last() { req testoperator "$A" GET "$PU/last" -; jb .description; }
chk $C "last purchase = latest valid by date" "Café 500 g" "$(last)"
mkp "$(d '-70 days')" "Café 1 kg" a; chk $C "idempotent replay -> 200 same purchase" "200 $PA" "$CODE $(jb .id)"
req testoperator "$A" POST "$PU" "$(jq -cn --arg t "$(d '-69 days')" '{purchasedAt:$t,description:"Otra"}')" -H "Idempotency-Key: qa340-$RUN-a"
chk $C "same key, different body -> 409" 409 "$CODE"
mkp "$(date -u -d '+3 years' +%FT00:00:00Z)" "Futuro" f; chk $C "future purchase date -> 400" 400 "$CODE"
req testviewer "$A" POST "$PU" "$(jq -cn --arg t "$(d '-2 days')" '{purchasedAt:$t,description:"V"}')" -H "Idempotency-Key: qa340-$RUN-v"
chk $C "VIEWER cannot record a purchase" 403 "$CODE"
req testoperator "$A" GET "$PU/$PB" -
req testoperator "$A" PUT "$PU/$PB" "$(jq -cn --arg t "$(d '-90 days')" '{purchasedAt:$t,description:"Café 500 g corregida"}')" -H "If-Match: $(etag)"
chk $C "correct a purchase date" 200 "$CODE"; chk $C "last purchase follows the correction" "Café 1 kg" "$(last)"
req testoperator "$A" GET "$PU/$PA" -
req testoperator "$A" DELETE "$PU/$PA" - -H "If-Match: $(etag)"; chk $C "void the latest purchase" 204 "$CODE"
chk $C "last purchase after voiding the latest" "Café 500 g corregida" "$(last)"
req testoperator "$A" GET "$PU/$PA/history" -; chk $C "purchase history keeps RECORDED and VOIDED" '["VOIDED","RECORDED"]' "$(jb '[.events[].type]|@json')"

# --- due / overdue queue and dispositions --------------------------------------------------------------------------
ev() { req testoperator "$A" GET "/api/customers/$1/follow-up-eligibility" -; jb "$2"; }
fpv() { req testoperator "$A" GET "/api/customers/$1/follow-up-policy" -; etag; }
req testuser "$A" GET /api/follow-up-policy -; TPOL=$(jb '"\(.cadenceDays) \(.timeZone)"')
rec $C "tenant A policy (read only, not modified by this script)" NOTE "cadence/timezone=$TPOL"
chk $C "last valid purchase 90 days ago + tenant cadence -> OVERDUE from LAST_PURCHASE" "OVERDUE LAST_PURCHASE" "$(ev "$CA" '[.status,.timingSource]|join(" ")')"
req testoperator "$A" GET "/api/follow-up-queue?status=OVERDUE&limit=100" -
chk $C "OVERDUE filter lists the customer" "$CA" "$(printf '%s' "$BODY" | jq -r --arg c "$CA" '.items[]|select(.customerId==$c)|.customerId')"
TODAY=$(ev "$CA" .tenantDate)
req testoperator "$A" PUT "/api/customers/$CA/follow-up-policy" "$(jq -cn --arg d "$TODAY" '{cadenceDays:null,explicitNextDate:$d}')" -H "If-Match: $(fpv "$CA")"
chk $C "explicit next date = today" 200 "$CODE"; chk $C "explicit date today -> DUE from EXPLICIT_DATE" "DUE EXPLICIT_DATE" "$(ev "$CA" '[.status,.timingSource]|join(" ")')"
req testoperator "$A" GET "/api/follow-up-queue?status=DUE&limit=100" -
chk $C "DUE filter lists the customer" "$CA" "$(printf '%s' "$BODY" | jq -r --arg c "$CA" '.items[]|select(.customerId==$c)|.customerId')"
req testoperator "$A" PUT "/api/customers/$CA/follow-up-snooze" '{"until":"2026-01-01"}' -H "If-Match: $(fpv "$CA")"
chk $C "snooze into the past -> 400" 400 "$CODE"
req testoperator "$A" PUT "/api/customers/$CA/follow-up-snooze" "$(jq -cn --arg d "$(date -u -d '+5 days' +%F)" '{until:$d}')" -H "If-Match: $(fpv "$CA")"
chk $C "snooze a DUE customer" 200 "$CODE"; chk $C "snoozed -> NOT_YET_DUE" NOT_YET_DUE "$(ev "$CA" .status)"
req testoperator "$A" POST "/api/customers/$CA/follow-up-dismissals" '{"notes":"x"}' -H "If-Match: $(fpv "$CA")" -H "Idempotency-Key: qa340-$RUN-dx"
chk $C "dismiss a NOT_YET_DUE customer -> 409" 409 "$CODE"
req testoperator "$A" PUT "/api/customers/$CA/follow-up-snooze" '{"until":null}' -H "If-Match: $(fpv "$CA")"
chk $C "snooze without a date -> 400 (no clear-snooze operation, ADR 0013)" 400 "$CODE"
# completion on CA, dismissal on a second customer
req testoperator "$A" POST "/api/customers/$CA/manual-follow-ups" '{"notes":"Llamada QA340"}' -H "If-Match: $(fpv "$CA")" -H "Idempotency-Key: qa340-$RUN-c1"
chk $C "early manual completion of a snoozed customer (allowed by ADR 0013)" 201 "$CODE"; COMP=$(jb .id)
req testoperator "$A" POST "/api/customers/$CA/manual-follow-ups" '{"notes":"otra"}' -H "If-Match: $(fpv "$CA")" -H "Idempotency-Key: qa340-$RUN-c1"
chk $C "completion replay (same key) -> 200, original record" "200 $COMP" "$CODE $(jb .id)"
chk $C "one completion stored" 1 "$(psqlq "select count(*) from dokene.manual_follow_up_completions where customer_id='$CA'")"
chk $C "completed customer advances to NOT_YET_DUE" NOT_YET_DUE "$(ev "$CA" .status)"
req testviewer "$A" POST "/api/customers/$CA/manual-follow-ups" '{}' -H "If-Match: $(fpv "$CA")" -H "Idempotency-Key: qa340-$RUN-cv"
chk $C "VIEWER cannot record a disposition" 403 "$CODE"
mkcust CD "QA340 Descartar Regresión" || exit 1
req testoperator "$A" POST "/api/customers/$CD/manual-follow-ups" '{}' -H 'If-Match: "0"' -H "Idempotency-Key: qa340-$RUN-st"
chk $C "stale If-Match on a disposition -> 409" 409 "$CODE"
req testoperator "$A" POST "/api/customers/$CD/follow-up-dismissals" '{"notes":"No interesado"}' -H "If-Match: $(fpv "$CD")" -H "Idempotency-Key: qa340-$RUN-d1"
chk $C "dismiss a DUE customer" 201 "$CODE"; chk $C "dismissed -> NOT_YET_DUE" NOT_YET_DUE "$(ev "$CD" .status)"
req testoperator "$A" GET "/api/follow-up-queue?limit=100" -
chk $C "dispositioned customers leave the due queue" 0 "$(printf '%s' "$BODY" | jq --arg a "$CA" --arg b "$CD" '[.items[]|select(.customerId==$a or .customerId==$b)]|length')"
req testoperator "$A" GET /api/follow-up-policy -; TV=$(etag)
req testoperator "$A" PUT /api/follow-up-policy "$(jq -cn --arg z "${TPOL#* }" '{cadenceDays:30,timeZone:$z}')" -H "If-Match: $TV"
chk $C "OPERATOR cannot change the tenant-wide policy (#159)" 403 "$CODE"

# --- archive transitions -------------------------------------------------------------------------------------------
mkcust CX "QA340 Archivar Regresión" || exit 1
req testoperator "$A" GET "/api/customers/$CX" -; XV=$(etag)
req testoperator "$A" DELETE "/api/customers/$CX" - -H "If-Match: $XV"; chk $C "OPERATOR cannot archive (CUSTOMER_DELETE)" 403 "$CODE"
req testuser "$A" DELETE "/api/customers/$CX" -; chk $C "archive without If-Match -> 400" 400 "$CODE"
req testuser "$A" DELETE "/api/customers/$CX" - -H "If-Match: $XV"; chk $C "OWNER archives the customer" 204 "$CODE"
req testuser "$A" DELETE "/api/customers/$CX" - -H "If-Match: $XV"
chk $C "archive is idempotent: a second archive with the same If-Match -> 204 (ADR 0009)" 204 "$CODE"
req testoperator "$A" GET "/api/customers/$CX" -; chk $C "archived profile still readable by id, status ARCHIVED" "200 ARCHIVED" "$CODE $(jb .status)"
req testoperator "$A" GET "/api/customers?name=QA340%20Archivar" -; chk $C "default list excludes the archived profile" 0 "$(jb '.customers|length')"
req testoperator "$A" GET "/api/customers?name=QA340%20Archivar&status=ARCHIVED" -; chk $C "ARCHIVED filter lists it" "$CX" "$(jb '.customers[0].id')"
chk $C "archived -> not eligible (CUSTOMER_ARCHIVED)" CUSTOMER_ARCHIVED "$(req testoperator "$A" GET "/api/customers/$CX/contact-eligibility?channel=WHATSAPP&contactId=$CX_C" -; jb '.reasons|join(",")')"
req testoperator "$A" GET "/api/follow-up-queue?limit=100" -
chk $C "archived customer leaves the due queue" 0 "$(printf '%s' "$BODY" | jq --arg a "$CX" '[.items[]|select(.customerId==$a)]|length')"
req testoperator "$A" POST "/api/customers/$CX/purchases" "$(jq -cn --arg t "$(d '-1 days')" '{purchasedAt:$t,description:"Tras archivo"}')" -H "Idempotency-Key: qa340-$RUN-xa"
rec $C "archived customer accepts no new purchase (ADR 0011)" "$( { [ "$CODE" = 409 ] || [ "$CODE" = 400 ]; } && echo PASS || echo FAIL)" "http=$CODE code=$(code)"
req testoperator "$A" GET "/api/customers/$CX/purchases" -; chk $C "archived customer's purchase history stays readable" "200 1" "$CODE $(jb '.purchases|length')"
req testoperator "$A" POST "/api/customers" "$(jq -cn --arg p "$(psqlq "select normalized_phone from dokene.customer_phone_contacts where id='$CX_C'")" '{displayName:"QA340 Reusar",phones:[{number:$p,region:"CL",primary:true}]}')"
chk $C "archived customer's phone stays reserved; creation never reactivates" 409 "$CODE"
req testoperator "$A" POST "/api/customers/$CX/manual-follow-ups" '{}' -H "If-Match: $(fpv "$CX")" -H "Idempotency-Key: qa340-$RUN-xc"
rec $C "no disposition on an archived customer" "$( { [ "$CODE" = 409 ] || [ "$CODE" = 400 ]; } && echo PASS || echo FAIL)" "http=$CODE code=$(code)"
req testoperator "$A" POST "/api/customers/$CX/recommendation" '{}'
rec $C "AI recommendation on an archived customer is refused/ineligible without a provider call" "$(case "$CODE" in 409|422) echo PASS;; 200) [ "$(jb '.outcome // .status')" = INELIGIBLE ] && echo PASS || echo FAIL;; *) echo FAIL;; esac)" "http=$CODE outcome=$(jb '.outcome // .status // .code')"

rec $C "no provider request during REG-01 API" "$([ "$(stub_sends)" = "$S0" ] && echo PASS || echo FAIL)" "stub_delta=$(( $(stub_sends) - S0 ))"
qa_finish
