#!/usr/bin/env bash
# QA suite #123 RES-05 (derived from the suite #121 script, adds a role downgrade to VIEWER during the backoff):  SEC-09 membership revoke / archive / DNC during a controlled retry, and plain membership revoke in flight. Provider = stub (FAULT INJECTION).
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa121/s/env121.sh; echo 0 > $STUB/delay-ok.ms
reqs() { wc -l < $STUB/requests.log; }; setstub() { echo "${1:-0}" > $STUB/delay.ms; echo "${2:-}" > $STUB/fail; }
echo '{"recommendation":{"outcome":"ACTION","action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE","rationale":"Reposición habitual.","confidence":0.8,"draftVariables":[]}}' > $STUB/rec.json
mkt() { local tok=$(curl -s -b $QA_TMP/jar-testuser.txt $BFF/api/session|jq -r .csrfToken); curl -s -b $QA_TMP/jar-testuser.txt -X POST $BFF/api/tenants -H "X-CSRF-TOKEN: $tok" -H 'Content-Type: application/json' -H "Idempotency-Key: sec121-$RUN-$1" -d "{\"displayName\":\"SEC121 $1 $RUN\"}"|jq -r .tenantId; }
mkc() { api testuser POST /api/customers "$(jq -nc --arg n "$1" --arg p "$(rnd_phone)" '{displayName:$n,phones:[{number:$p,region:"CL",primary:true}]}')"; echo "$BODY"|jq -r .id; }
fresh() { local id=$(mk "$1" "$(rnd_phone)" ""); consent $id GRANTED >/dev/null; buy $id 2026-08-01T14:00:00Z "Café de grano 1 kg" >/dev/null; echo $id; }
bg_call() { local u=$1 cid=$2 out=$3 j=$QA_TMP/jar-$1.txt; local tok=$(curl -s -b $j $BFF/api/session|jq -r .csrfToken); api $u GET /api/customers/$cid/follow-up-policy ''; local v=$(etag)
  ( curl -s -b $j -X POST "$BFF/api/customers/$cid/recommendation" -H "X-Tenant-Id: $TENANT" -H "X-CSRF-TOKEN: $tok" -H "If-Match: \"$v\"" -w '\n%{http_code}' > $out ) & }
verdict() { local code=$(tail -1 $1); local body=$(head -n -1 $1); echo "$code $(echo "$body"|jq -r '.status // "-"' 2>/dev/null) $(echo "$body"|jq -r '.unavailableReason // "-"' 2>/dev/null)"; }
scenario() { # label tenantname delayms fail action(member|archive|dnc|consent)
  local lab=$1 tn=$2 delay=$3 fail=$4 act=$5; setstub 0 ""; TENANT=$(mkt "$tn"); api testuser POST /api/memberships "{\"identityId\":\"$UO\",\"role\":\"OPERATOR\"}" >/dev/null
  local cid=$(fresh "Inflight $lab $RUN"); setstub $delay "$fail"; local n0=$(reqs) f=$QA_TMP/sc-$tn; bg_call testoperator $cid $f; sleep 1.2
  case $act in
   member) api testuser DELETE /api/memberships/$UO '' ;;
   role) api testuser PUT /api/memberships/$UO/role '{"role":"VIEWER"}' ;;
   archive) api testuser DELETE /api/customers/$cid '' -H 'If-Match: "0"' ;;
   dnc) local v=$(getj testuser /api/customers/$cid/contact-policy | jq .version); api testuser PUT /api/customers/$cid/do-not-contact '{"enabled":true,"source":"CUSTOMER_WRITTEN"}' -H "If-Match: \"$v\"" ;;
   consent) local pid=$(getj testuser /api/customers/$cid | jq -r '.phones[0].id') v=$(getj testuser /api/customers/$cid/contact-policy | jq .version); api testuser PUT /api/customers/$cid/contacts/$pid/consents/WHATSAPP '{"status":"REVOKED","source":"CUSTOMER_VERBAL"}' -H "If-Match: \"$v\"" ;;
  esac; local ac=$CODE; wait; local r=$(verdict $f) n=$(( $(reqs) - n0 ))
  echo "  [$lab] authority change rc=$ac -> result: $r  provider requests=$n"
  chk SEC-09 "$lab: result not delivered as AVAILABLE" yes "$(echo "$r" | grep -q AVAILABLE && echo no || echo yes)"
  echo $n > $QA_TMP/n-last; }
echo "== plain in-flight (no retry; provider delayed 4 s, change at ~1.2 s)"
for act in member role archive dnc consent; do scenario "inflight-$act" "i-$act" 4000 "" $act; done
echo "== controlled retry (first attempt: 503 after 2.5 s; change at ~1.2 s; a second attempt would be a 2nd provider request)"
for act in member role archive dnc consent; do scenario "retry-$act" "r-$act" 2500 "503:1" $act; n=$(cat $QA_TMP/n-last); chk SEC-09 "retry-$act: revoked authority prevents the 2nd attempt from transmitting context" 1 "$n"; done
echo "== control: no change during a retry -> 2 provider requests (proves the retry really happens)"
setstub 0 ""; TENANT=$(mkt ctl); api testuser POST /api/memberships "{\"identityId\":\"$UO\",\"role\":\"OPERATOR\"}" >/dev/null; cid=$(fresh "Inflight control $RUN"); setstub 2500 "503:1"; n0=$(reqs); f=$QA_TMP/sc-ctl; bg_call testuser $cid $f; wait; echo "  control: $(verdict $f) provider requests=$(( $(reqs) - n0 ))"; chk SEC-09 "control: retry happens when nothing changed" 2 "$(( $(reqs) - n0 ))"; setstub 0 ""
echo "PASS=$PASSN FAIL=$FAILN"
