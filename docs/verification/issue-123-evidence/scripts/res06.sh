#!/usr/bin/env bash
# QA suite #123, RES-05 (authority during retry, rerun of the suite #121 script on the current SHA) is run separately; RES-06 business drift while a provider call is in flight (injected 4 s provider latency; versioned and non-versioned drift).
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa123/s/base.sh; ST=/home/stevdrey/qa121/stub
echo '{"recommendation":{"outcome":"ACTION","action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE","rationale":"Reposición habitual del café.","confidence":0.8,"draftVariables":[]}}' > $ST/rec.json
echo '{"draft":{"outcome":"DRAFT","action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE","body":"Hola, ¿cómo te fue con el Café de grano 1 kg que compraste?","draftVariables":[],"locale":"es-419","evidence":["Compra: Café de grano 1 kg"],"warnings":[],"rationale":"Reposición.","confidence":0.9}}' > $ST/draft.json
: > $ST/fail; echo 0 > $ST/delay.ms; echo 4000 > $ST/delay-ok.ms
fresh() { local id=$(mk "$1" "$(rnd_phone)" ""); consent $id GRANTED >/dev/null; buy $id 2026-08-01T14:00:00Z "Café de grano 1 kg" >/dev/null; echo $id; }
bg_call() { local kind=$1 cid=$2 out=$3 j=$QA_TMP/jar-testoperator.txt tok v; tok=$(curl -s -b $j $BFF/api/session|jq -r .csrfToken); api testoperator GET /api/customers/$cid/follow-up-policy ''; v=$(etag)
  if [ $kind = rec ]; then ( curl -s -b $j -X POST "$BFF/api/customers/$cid/recommendation" -H "X-Tenant-Id: $TENANT" -H "X-CSRF-TOKEN: $tok" -H "If-Match: \"$v\"" -w '\n%{http_code}' > $out ) &
  else ( curl -s -b $j -X POST "$BFF/api/customers/$cid/draft" -H "X-Tenant-Id: $TENANT" -H "X-CSRF-TOKEN: $tok" -H 'Content-Type: application/json' -d '{"action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE"}' -H "If-Match: \"$v\"" -w '\n%{http_code}' -D $out.h > $out ) & fi; }
verdict() { local f=$1; local code=$(tail -1 $f) body=$(head -n -1 $f); echo "http=$code status=$(echo "$body"|jq -r '.status // "-"' 2>/dev/null) reason=$(echo "$body"|jq -r '.unavailableReason // "-"' 2>/dev/null) delivered_content=$(echo "$body"|jq -r '(.recommendation!=null) or (.draft!=null)' 2>/dev/null)"; }
drift() { # label kind action_function expected_regex_of_status
  local lab=$1 kind=$2 act=$3 exp=$4; local cid=$(fresh "Drift $lab $RUN") n0=$(wc -l < $ST/requests.log) f=$QA_TMP/dr-$RANDOM; sleep 3.3; bg_call $kind $cid $f; sleep 1.3; $act $cid; local ac=$CODE; wait; local r=$(verdict $f); echo "[$kind] $lab: change rc=$ac -> $r | audit=$(ai_last) | provider requests=$(( $(wc -l < $ST/requests.log) - n0 ))"
  local st=$(echo "$r" | sed -n 's/.*status=\([A-Z_]*\).*/\1/p'); chk RES-06 "[$kind] $lab: result is $exp (a versioned stale result is HTTP 409 without content) and no AVAILABLE content is delivered" yes "$( ( echo "$st" | grep -Eq "^($exp)$" || { echo "$r" | grep -q 'http=409' && echo "$exp" | grep -q STALE_STATE; } ) && ! echo "$r" | grep -q 'delivered_content=true' && echo yes || echo "$st/$r")"; }
a_cadence() { api testoperator GET /api/customers/$1/follow-up-policy ''; local v=$(etag); api testoperator PUT /api/customers/$1/follow-up-policy '{"cadenceDays":20}' -H "If-Match: \"$v\""; }
a_purchase() { api testoperator POST /api/customers/$1/purchases '{"purchasedAt":"'$(date -u +%FT%H:%M:%SZ)'","description":"Compra nueva durante la consulta"}' -H "Idempotency-Key: drift-$RUN-$RANDOM"; }
a_snooze() { api testoperator GET /api/customers/$1/follow-up-policy ''; local v=$(etag); api testoperator PUT /api/customers/$1/follow-up-snooze '{"until":"2030-01-01"}' -H "If-Match: \"$v\""; }
a_dismiss() { api testoperator GET /api/customers/$1/follow-up-policy ''; local v=$(etag); api testoperator POST /api/customers/$1/follow-up-dismissals '{"notes":"drift"}' -H "If-Match: \"$v\"" -H "Idempotency-Key: dis-$RUN-$RANDOM"; }
a_manual() { api testoperator GET /api/customers/$1/follow-up-policy ''; local v=$(etag); api testoperator POST /api/customers/$1/manual-follow-ups '{"notes":"drift"}' -H "If-Match: \"$v\"" -H "Idempotency-Key: man-$RUN-$RANDOM"; }
a_consent() { local pid=$(getj testoperator /api/customers/$1 | jq -r '.phones[0].id') v=$(getj testoperator /api/customers/$1/contact-policy | jq .version); api testoperator PUT /api/customers/$1/contacts/$pid/consents/WHATSAPP '{"status":"REVOKED","source":"CUSTOMER_VERBAL"}' -H "If-Match: \"$v\""; }
a_dnc() { local v=$(getj testoperator /api/customers/$1/contact-policy | jq .version); api testoperator PUT /api/customers/$1/do-not-contact '{"enabled":true,"source":"CUSTOMER_WRITTEN"}' -H "If-Match: \"$v\""; }
a_archive() { api testuser DELETE /api/customers/$1 '' -H 'If-Match: "0"'; }
a_rename() { local v=$(getj testoperator /api/customers/$1 | jq .version); api testoperator PUT /api/customers/$1 "{\"displayName\":\"Renombrado durante la consulta\",\"phones\":[$(getj testoperator /api/customers/$1 | jq -c '[.phones[]|{number,region,primary}]'|sed 's/^\[//;s/\]$//')]}" -H "If-Match: \"$v\""; }
for kind in rec draft; do
  echo "== RES-06 $kind: drift while the provider call is in flight (injected 4 s latency)"
  drift "cadence changed (versioned)" $kind a_cadence "STALE_STATE|INELIGIBLE"
  drift "snoozed (versioned)" $kind a_snooze "STALE_STATE|INELIGIBLE"
  drift "dismissed (versioned)" $kind a_dismiss "STALE_STATE|INELIGIBLE"
  drift "manual follow-up recorded (versioned)" $kind a_manual "STALE_STATE|INELIGIBLE"
  drift "new purchase recorded (non-versioned)" $kind a_purchase "STALE_STATE|INELIGIBLE|AVAILABLE"
  drift "consent revoked (non-versioned)" $kind a_consent "INELIGIBLE"
  drift "do-not-contact enabled (non-versioned)" $kind a_dnc "INELIGIBLE"
  drift "customer archived (non-versioned)" $kind a_archive "INELIGIBLE"
done
echo "  note: for 'new purchase' an AVAILABLE result is acceptable only if the evaluation is still due; the delivered text is not asserted"
: > $ST/fail; echo 0 > $ST/delay-ok.ms
echo "PASS=$PASSN FAIL=$FAILN"
