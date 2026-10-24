#!/usr/bin/env bash
# QA suite #121: SEC-09 in-flight authority changes and SEC-10 abuse limits. Provider = local stub (FAULT INJECTION: controllable delay/503), no OpenAI cost.
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa121/s/env121.sh
reqs() { wc -l < $STUB/requests.log; }
setstub() { echo "${1:-0}" > $STUB/delay.ms; echo "${2:-}" > $STUB/fail; }
echo '{"recommendation":{"outcome":"ACTION","action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE","rationale":"Reposición habitual.","confidence":0.8,"draftVariables":[]}}' > $STUB/rec.json
echo '{"draft":{"outcome":"DRAFT","action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE","body":"Hola, ¿cómo te fue con el Café de grano 1 kg que compraste?","draftVariables":[],"locale":"es-419","evidence":["Compra: Café de grano 1 kg"],"warnings":[],"rationale":"Reposición.","confidence":0.9}}' > $STUB/draft.json
fresh() { local id=$(mk "$1" "$(rnd_phone)" ""); consent $id GRANTED >/dev/null; buy $id 2026-08-01T14:00:00Z "Café de grano 1 kg" >/dev/null; echo $id; }
bg_call() { # user kind customer outfile : fire the AI call in background with a fresh policy ETag
  local u=$1 kind=$2 cid=$3 out=$4 j=$QA_TMP/jar-$1.txt; local tok=$(curl -s -b $j $BFF/api/session|jq -r .csrfToken); api $u GET /api/customers/$cid/follow-up-policy ''; local v=$(etag)
  if [ $kind = rec ]; then ( curl -s -b $j -X POST "$BFF/api/customers/$cid/recommendation" -H "X-Tenant-Id: $TENANT" -H "X-CSRF-TOKEN: $tok" -H "If-Match: \"$v\"" -w '\n%{http_code}' > $out ) &
  else ( curl -s -b $j -X POST "$BFF/api/customers/$cid/draft" -H "X-Tenant-Id: $TENANT" -H "X-CSRF-TOKEN: $tok" -H 'Content-Type: application/json' -d '{"action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE"}' -H "If-Match: \"$v\"" -w '\n%{http_code}' > $out ) & fi; }
verdict() { local f=$1; local code=$(tail -1 $f); local body=$(head -n -1 $f); echo "$code $(echo "$body"|jq -r '.status // "-"' 2>/dev/null) $(echo "$body"|jq -r '.unavailableReason // "-"' 2>/dev/null)"; }
revoke_consent() { local pid=$(getj testoperator /api/customers/$1 | jq -r '.phones[0].id') v=$(getj testoperator /api/customers/$1/contact-policy | jq .version); api testoperator PUT /api/customers/$1/contacts/$pid/consents/WHATSAPP '{"status":"REVOKED","source":"CUSTOMER_VERBAL"}' -H "If-Match: \"$v\""; }
echo "== SEC-09a consent revoked during a recommendation call (provider delayed 4 s)"
for kind in rec draft; do
  setstub 4000 ""; cid=$(fresh "Inflight Consent $kind $RUN"); n0=$(reqs); f=$QA_TMP/if-$kind; bg_call testoperator $kind $cid $f; sleep 1.5; revoke_consent $cid; chk SEC-09 "$kind: consent revoke applied during the call" 200 "$CODE"; wait
  r=$(verdict $f); echo "  result during revoke ($kind): $r  audit=$(ai_last)"; chk SEC-09 "$kind: in-flight result is NOT delivered as AVAILABLE after consent revoke" yes "$(echo "$r" | grep -q AVAILABLE && echo no || echo yes)"; done
echo "== SEC-09b consent revoked during the backoff between two attempts (first attempt 503 after 2.5 s)"
for kind in rec draft; do
  setstub 2500 "503:1"; cid=$(fresh "Inflight Retry $kind $RUN"); n0=$(reqs); f=$QA_TMP/rt-$kind; bg_call testoperator $kind $cid $f; sleep 1.2; revoke_consent $cid; wait
  r=$(verdict $f); n=$(( $(reqs) - n0 )); echo "  result ($kind): $r  provider requests=$n  audit=$(ai_last)"
  chk SEC-09 "$kind: not delivered as AVAILABLE" yes "$(echo "$r" | grep -q AVAILABLE && echo no || echo yes)"
  chk SEC-09 "$kind: revoked authority prevents the second attempt from transmitting context (1 provider request)" 1 "$n"; done
echo "== SEC-09c membership revoked during the call (operator in tenant C)"
setstub 4000 ""; TENANT=$C; CCX=$(fresh "Inflight Member $RUN" 2>/dev/null); [ -z "$CCX" ] && { api testuser GET /api/customers '' ; }
# create in C with owner (operator may not own); use testuser
mkc() { api testuser POST /api/customers "$(jq -nc --arg n "$1" --arg p "$(rnd_phone)" '{displayName:$n,phones:[{number:$p,region:"CL",primary:true}]}')"; echo "$BODY"|jq -r .id; }
setstub 0 ""; CM=$(mkc "Inflight Member C $RUN"); consent testuser $CM >/dev/null; buy testuser $CM 2026-08-01T14:00:00Z "Café de grano 1 kg" >/dev/null
setstub 4000 ""; n0=$(reqs); f=$QA_TMP/mem; bg_call testoperator rec $CM $f; sleep 1.5; api testuser DELETE /api/memberships/$UO ''; chk SEC-09 "operator membership in C revoked during the call" 204 "$CODE"; wait
r=$(verdict $f); echo "  result: $r  provider requests=$(( $(reqs) - n0 ))"; chk SEC-09 "in-flight result is not delivered as AVAILABLE after membership revoke" yes "$(echo "$r" | grep -q AVAILABLE && echo no || echo yes)"
api testoperator GET /api/customers ''; chk SEC-09 "revoked operator now denied on C" 403 "$CODE"
echo "== SEC-09d customer archived during the call"
TENANT=$A; setstub 4000 ""; cid=$(fresh "Inflight Archive $RUN"); n0=$(reqs); f=$QA_TMP/arch; bg_call testoperator rec $cid $f; sleep 1.5; api testuser DELETE /api/customers/$cid '' -H 'If-Match: "0"'; chk SEC-09 "customer archived during the call" 204 "$CODE"; wait
r=$(verdict $f); echo "  result: $r audit=$(ai_last)"; chk SEC-09 "in-flight result is not delivered as AVAILABLE after archive" yes "$(echo "$r" | grep -q AVAILABLE && echo no || echo yes)"
echo "== SEC-09e do-not-contact enabled during the call"
setstub 4000 ""; cid=$(fresh "Inflight DNC $RUN"); f=$QA_TMP/dnc; bg_call testoperator rec $cid $f; sleep 1.5; v=$(getj testoperator /api/customers/$cid/contact-policy | jq .version); api testoperator PUT /api/customers/$cid/do-not-contact '{"enabled":true,"source":"CUSTOMER_WRITTEN"}' -H "If-Match: \"$v\""; chk SEC-09 "DNC applied during the call" 200 "$CODE"; wait
r=$(verdict $f); echo "  result: $r"; chk SEC-09 "in-flight result is not delivered as AVAILABLE after DNC" yes "$(echo "$r" | grep -q AVAILABLE && echo no || echo yes)"
setstub 0 ""
echo "== SEC-10 abuse limits (actor 20/min, tenant 60/min, Retry-After 5 s); provider = instant stub"
sleep 65; for u in testuser testoperator testviewer; do qa_login $u; done; TENANT=$A; cid=$(fresh "Burst Customer $RUN"); api testoperator GET /api/customers/$cid/follow-up-policy ''; v=$(etag); j=$QA_TMP/jar-testoperator.txt; tok=$(curl -s -b $j $BFF/api/session|jq -r .csrfToken); n0=$(reqs); ok=0; lim=0; ra=""
for i in $(seq 1 25); do o=$(curl -s -D $QA_TMP/bh -o $QA_TMP/bb -w '%{http_code}' -b $j -X POST "$BFF/api/customers/$cid/recommendation" -H "X-Tenant-Id: $A" -H "X-CSRF-TOKEN: $tok" -H "If-Match: \"$v\""); if [ $o = 200 ]; then ok=$((ok+1)); elif [ $o = 429 ]; then lim=$((lim+1)); ra=$(grep -i '^retry-after' $QA_TMP/bh|tr -d '\r'|awk '{print $2}'); body429=$(cat $QA_TMP/bb); fi; done
echo "  25 rapid calls by testoperator: 200=$ok 429=$lim Retry-After=$ra  provider requests=$(( $(reqs) - n0 ))  429 body: $(echo "$body429"|cut -c1-160)"
chk SEC-10 "calls beyond the actor limit get 429" yes "$([ $lim -ge 1 ] && echo yes || echo no)"; chk SEC-10 "at most 20 calls pass in the window" yes "$([ $ok -le 20 ] && echo yes || echo "$ok")"
chk SEC-10 "429 carries Retry-After" yes "$([ -n "$ra" ] && echo yes || echo no)"; chk SEC-10 "429 responses made no provider request" "$ok" "$(( $(reqs) - n0 ))"
chk SEC-10 "429 body does not leak internals" yes "$(echo "$body429" | grep -Eiq 'exception|stack|openai|sk-|limit.*[0-9]+ ?/' && echo no || echo yes)"
api testuser GET /api/customers/$cid/follow-up-policy ''; vu=$(etag); api testuser POST /api/customers/$cid/recommendation '' -H "If-Match: \"$vu\""; chk SEC-10 "independent actor (owner, same tenant) is not throttled by the operator's burst" 200 "$CODE"
TENANT=$B; CBX=$(mkc "Burst B $RUN"); consent testuser $CBX >/dev/null; buy testuser $CBX 2026-08-01T14:00:00Z "Café" >/dev/null; api testoperator GET /api/customers/$CBX/follow-up-policy ''; vb=$(etag); api testoperator POST /api/customers/$CBX/recommendation '' -H "If-Match: \"$vb\""; echo "  same actor in tenant B right after: $CODE"
chk SEC-10 "same actor in another tenant (B): observe (actor limit is per actor or per actor+tenant)" OBS OBS
TENANT=$A; api testoperator POST /api/customers/$cid/recommendation '' -H "If-Match: \"$v\""; chk SEC-10 "still throttled immediately after (same window)" 429 "$CODE"
echo "  waiting 70 s for the window to reset"; sleep 70; for u in testuser testoperator testviewer; do qa_login $u; done; TENANT=$A; api testoperator POST /api/customers/$cid/recommendation '' -H "If-Match: \"$v\""; chk SEC-10 "after the window the actor can call again" 200 "$CODE"
n0=$(reqs); for i in 1 2 3; do api testviewer POST /api/customers/$cid/recommendation '' -H "If-Match: \"$v\""; done; chk SEC-10 "denied (403) calls by VIEWER create no provider request" 0 "$(( $(reqs) - n0 ))"
echo "PASS=$PASSN FAIL=$FAILN"
