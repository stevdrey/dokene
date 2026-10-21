#!/usr/bin/env bash
# QA suite #123 RES-10 observability. Disposable override exposes /actuator/metrics (NOT the default). Provider = stub (injected). Audit rows and log lines are read for each invocation outcome; meters are read through an authenticated session.
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa123/s/base.sh; ST=/home/stevdrey/qa121/stub; qa_login testoperator; TENANT=$A; qa_login testuser; qa_login testviewer
B1=$(mk "Res10 obs $RUN" "$(rnd_phone)" ""); consent $B1 GRANTED >/dev/null; buy $B1 2026-08-01T14:00:00Z "Café de grano 1 kg" >/dev/null
REC_OK='{"recommendation":{"outcome":"ACTION","action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE","rationale":"Reposición habitual del café.","confidence":0.8,"draftVariables":[]}}'
DR_OK='{"draft":{"outcome":"DRAFT","action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE","body":"Hola, ¿cómo te fue con el Café de grano 1 kg que compraste?","draftVariables":[],"locale":"es-419","evidence":["Compra: Café de grano 1 kg"],"warnings":[],"rationale":"Reposición.","confidence":0.9}}'
echo "$REC_OK" > $ST/rec.json; echo "$DR_OK" > $ST/draft.json; : > $ST/fail; echo 0 > $ST/delay.ms; echo 0 > $ST/delay-ok.ms
m() { curl -s -b $QA_TMP/jar-testoperator.txt -H "X-Tenant-Id: $A" "localhost:8080/actuator/metrics/$1${2:+?tag=$2}"; }
cnt() { local v; v=$(m "$1" "$2" | jq -r '[.measurements[]|select(.statistic=="COUNT" or .statistic=="VALUE")|.value]|add // 0' 2>/dev/null); printf '%.0f' "${v:-0}"; }
echo "== meters present"; curl -s -b $QA_TMP/jar-testoperator.txt -H "X-Tenant-Id: $A" localhost:8080/actuator/metrics | jq -r '.names[]' | grep -i dokene | sed 's/^/  /'
rec_with() { api testoperator GET /api/customers/$B1/follow-up-policy ''; local v=$(etag); api testoperator POST /api/customers/$B1/recommendation '' -H "If-Match: \"$v\""; }
step() { # label failspec recjson
  : > $ST/fail; [ -n "$2" ] && echo "$2" > $ST/fail; [ -n "$3" ] && echo "$3" > $ST/rec.json || echo "$REC_OK" > $ST/rec.json
  local a0=$(cnt dokene.ai.attempts) o0=$(cnt dokene.ai.outcomes) t0=$(cnt dokene.ai.tokens) r0=$(ai_rows); local n0=$(wc -l < $ST/requests.log); sleep 3.3; rec_with; local a1=$(cnt dokene.ai.attempts) o1=$(cnt dokene.ai.outcomes) t1=$(cnt dokene.ai.tokens)
  echo "$1 -> http=$CODE status=$(echo "$BODY"|jq -r '.status') | provider requests=$(( $(wc -l < $ST/requests.log) - n0 )) | meters: attempts +$(echo "$a1 - $a0"|bc) outcomes +$(echo "$o1 - $o0"|bc) tokens +$(echo "$t1 - $t0"|bc) | audit rows +$(( $(ai_rows) - r0 )) ($(ai_last))"
  LAST_PROV=$(( $(wc -l < $ST/requests.log) - n0 )); LAST_A=$(echo "$a1 - $a0"|bc); LAST_O=$(echo "$o1 - $o0"|bc); LAST_AUD=$(( $(ai_rows) - r0 )); LAST_T=$(echo "$t1 - $t0"|bc); : > $ST/fail; }
echo "== invocations"
step "success" "" ""; chk RES-10 "success: 1 attempt, 1 outcome, 1 audit row" "1 1 1" "$LAST_A $LAST_O $LAST_AUD"; chk RES-10 "success: token usage counted (reported by the response: 1 input + 1 output)" 2 "$LAST_T"
step "503 once then success (retry)" "503:1" ""; chk RES-10 "retry: attempts counted individually (2), exactly 1 outcome, 1 audit row" "2 1 1" "$LAST_A $LAST_O $LAST_AUD"
step "503 always" "503:9" ""; chk RES-10 "failure after retry: 2 attempts, 1 outcome, 1 audit row" "2 1 1" "$LAST_A $LAST_O $LAST_AUD"; chk RES-10 "failed attempts add no token usage (none reported)" 0 "$LAST_T"
step "401 rejected" "401:9" ""; chk RES-10 "non-retryable failure: 1 attempt, 1 outcome, 1 audit row" "1 1 1" "$LAST_A $LAST_O $LAST_AUD"
step "model refusal" "" "REFUSAL"; chk RES-10 "refusal: 1 attempt, 1 outcome, 1 audit row" "1 1 1" "$LAST_A $LAST_O $LAST_AUD"
step "gate rejection (rationale with a link)" "" '{"recommendation":{"outcome":"ACTION","action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE","rationale":"Escribe a https://promo.test/ya","confidence":0.8,"draftVariables":[]}}'; chk RES-10 "gate rejection: 1 attempt, 1 outcome, 1 audit row (DENIED/INVALID_RECOMMENDATION)" "1 1 1" "$LAST_A $LAST_O $LAST_AUD"
a0=$(cnt dokene.ai.attempts); r0=$(ai_rows); sleep 3.3; api testviewer GET /api/customers/$B1/follow-up-policy ''; v=$(etag); api testviewer POST /api/customers/$B1/recommendation '' -H "If-Match: \"$v\""; echo "denied caller (VIEWER) -> http=$CODE | attempts +$(echo "$(cnt dokene.ai.attempts) - $a0"|bc) audit rows +$(( $(ai_rows) - r0 ))"; chk RES-10 "denied before the provider: 0 attempts, 0 audit rows" "0 0" "$(echo "$(cnt dokene.ai.attempts) - $a0"|bc) $(( $(ai_rows) - r0 ))"
echo "== meter details"; for n in dokene.ai.attempts dokene.ai.attempt.duration dokene.ai.outcomes; do echo "-- $n"; m $n | jq -c '{name,baseUnit,measurements:[.measurements[]|{statistic,value}],availableTags:[.availableTags[]|{tag,n:(.values|length)}]}' | sed 's/^/  /'; done
echo "-- outcomes by tag values"; m dokene.ai.outcomes | jq -r '.availableTags[]|"  \(.tag): \(.values|join(","))"'
echo "-- every dokene meter and its tags (cardinality review)"; for n in $(curl -s -b $QA_TMP/jar-testoperator.txt -H "X-Tenant-Id: $A" localhost:8080/actuator/metrics | jq -r '.names[]' | grep -i '^dokene'); do m $n | jq -c '{name,tags:[.availableTags[]|{tag,values:(.values|length)}]}'; done | sed 's/^/  /'
tags=$(for n in $(curl -s -b $QA_TMP/jar-testoperator.txt -H "X-Tenant-Id: $A" localhost:8080/actuator/metrics | jq -r '.names[]' | grep -i '^dokene'); do m $n | jq -r '.availableTags[].tag'; done | sort -u | tr '\n' ' '); echo "  all tag keys: $tags"; chk RES-10 "no tenant/customer/actor/correlation tag on any dokene meter" 0 "$(echo "$tags" | grep -Eic 'tenant|customer|actor|identity|correlation|user')"
echo "-- token usage meters (stub reports usage 1/1/2): $(curl -s -b $QA_TMP/jar-testoperator.txt -H "X-Tenant-Id: $A" localhost:8080/actuator/metrics | jq -r '.names[]' | grep -i 'token' | tr '\n' ' ')"
echo "== logs: one outcome line per invocation, correlation continuity"; LOG=$(docker compose logs --no-log-prefix --tail 400 backend 2>&1); echo "$LOG" | grep "AI invocation outcome" | tail -8 | sed -E 's/^.{24}//' | cut -c1-230 | sed 's/^/  /'
chk RES-10 "logs hold no prompt, note, phone, draft body or provider text" 0 "$(echo "$LOG" | grep -Eic 'Reposición habitual|Café de grano|promo.test|stub failure|server_error|+569')"
echo "== audit rows (closed vocabulary, no content columns)"; psqlq "select to_char(occurred_at,'HH24:MI:SS')||' '||ai_operation||' '||ai_outcome||' '||ai_detail||' '||outcome from dokene.audit_events where event_type='AI_INVOCATION_OUTCOME' order by occurred_at desc limit 8" | sed 's/^/  /'
echo "PASS=$PASSN FAIL=$FAILN"
