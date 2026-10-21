#!/usr/bin/env bash
# QA suite #121 gap closure: SEC-01 idle-session expiry and SEC-10 tenant-wide AI limit. Needs the override (DOKENE_SESSION_TIMEOUT=1m, DOKENE_AI_RATE_LIMIT_TENANT_PERMITS=25) and the stub (instant responses).
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa121/s/env121.sh; : > $STUB/fail; echo 0 > $STUB/delay.ms
echo '{"recommendation":{"outcome":"ACTION","action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE","rationale":"Reposición habitual.","confidence":0.8,"draftVariables":[]}}' > $STUB/rec.json
echo "== SEC-10 tenant-wide limit (25/min tenant, 20/min actor): operator 20 + owner 5 pass, the 26th tenant call is throttled for BOTH actors"
cid=$(mk "Tenant Limit $RUN" "$(rnd_phone)" ""); consent $cid GRANTED >/dev/null; buy $cid 2026-08-01T14:00:00Z "Café de grano 1 kg" >/dev/null; sleep 62; for u in testuser testoperator testviewer; do qa_login $u; done
call() { local u=$1 j tok; j=$QA_TMP/jar-$u.txt; tok=$(curl -s -b $j $BFF/api/session|jq -r .csrfToken); curl -s -D $QA_TMP/bh -o /dev/null -w '%{http_code}' -b $j -X POST "$BFF/api/customers/$cid/recommendation" -H "X-Tenant-Id: $TENANT" -H "X-CSRF-TOKEN: $tok" -H "If-Match: \"$V\""; }
api testoperator GET /api/customers/$cid/follow-up-policy ''; V=$(etag); n0=$(wc -l < $STUB/requests.log); okO=0; for i in $(seq 1 20); do [ "$(call testoperator)" = 200 ] && okO=$((okO+1)); done
okU=0; for i in $(seq 1 5); do [ "$(call testuser)" = 200 ] && okU=$((okU+1)); done
c26=$(call testuser); ra=$(grep -i '^retry-after' $QA_TMP/bh|tr -d '\r'|awk '{print $2}'); cO=$(call testoperator)
chk SEC-10 "operator 20/20 and owner 5/5 pass (25 tenant permits)" "20 5" "$okO $okU"; chk SEC-10 "26th call of the tenant (owner, who is within his own actor limit) -> 429" 429 "$c26"; chk SEC-10 "429 carries Retry-After" yes "$([ -n "$ra" ] && echo yes || echo no)"
chk SEC-10 "operator is also throttled (tenant bucket shared; his actor bucket is exhausted too)" 429 "$cO"; chk SEC-10 "throttled calls made no provider request" 25 "$(( $(wc -l < $STUB/requests.log) - n0 ))"
TENANT=$B; api testoperator GET /api/customers '' ; CB1=$(echo "$BODY"|jq -r '.customers[0].id'); api testoperator GET /api/customers/$CB1/follow-up-policy ''; VB=$(etag); api testoperator POST /api/customers/$CB1/recommendation '' -H "If-Match: \"$VB\""; chk SEC-10 "another tenant (B) is not throttled by A's tenant bucket" 200 "$CODE"
echo "== SEC-01 idle-session expiry (session timeout 1 min)"
TENANT=$A; qa_login testviewer; j=$QA_TMP/jar-testviewer.txt; r0=$(curl -s -o /dev/null -w '%{http_code}' -b $j -H "X-Tenant-Id: $A" $BFF/api/customers); echo "  fresh session -> $r0"; chk SEC-01 "fresh session works" 200 "$r0"
sleep 25; r1=$(curl -s -o /dev/null -w '%{http_code}' -b $j -c $j -H "X-Tenant-Id: $A" $BFF/api/customers); chk SEC-01 "activity within the window keeps the session alive (sliding)" 200 "$r1"
sleep 75; r2=$(curl -s -o /dev/null -w '%{http_code}' -b $j -H "X-Tenant-Id: $A" $BFF/api/customers); chk SEC-01 "after 75 s idle the session is expired -> 401 (not a redirect to HTML)" 401 "$r2"
tok=$(curl -s -b $j $BFF/api/session | jq -r .csrfToken); r3=$(curl -s -o /dev/null -w '%{http_code}' -b $j -X POST "$BFF/api/customers/$cid/recommendation" -H "X-Tenant-Id: $A" -H 'Content-Type: application/json'); chk SEC-01 "expired session on an AI POST -> 401 and no provider request" "401" "$r3"
echo "PASS=$PASSN FAIL=$FAILN"
