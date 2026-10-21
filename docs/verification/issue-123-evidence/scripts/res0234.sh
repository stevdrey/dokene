#!/usr/bin/env bash
# QA suite #123, RES-02/03/04 with the FAULT-INJECTION stub (every provider response below is injected, none is genuine). Records attempt counts and timestamps from the stub log.
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa123/s/base.sh; B1=$(cat /home/stevdrey/qa123/out/baseline.id); ST=/home/stevdrey/qa121/stub
echo '{"recommendation":{"outcome":"ACTION","action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE","rationale":"Reposición habitual del café.","confidence":0.8,"draftVariables":[]}}' > $ST/rec.json
echo '{"draft":{"outcome":"DRAFT","action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE","body":"Hola, ¿cómo te fue con el Café de grano 1 kg que compraste?","draftVariables":[],"locale":"es-419","evidence":["Compra: Café de grano 1 kg"],"warnings":[],"rationale":"Reposición.","confidence":0.9}}' > $ST/draft.json
: > $ST/fail; echo 0 > $ST/delay.ms
rect() { # timeoutMs(optional) -> sets R_CODE R_STATUS R_REASON R_RETRY R_MS
  local body=''; [ -n "$1" ] && body="{\"timeoutMs\":$1}"; api testoperator GET /api/customers/$B1/follow-up-policy ''; local v=$(etag)
  local t0=$(date +%s%N); api testoperator POST /api/customers/$B1/recommendation "$body" -H "If-Match: \"$v\""; R_MS=$(( ($(date +%s%N) - t0) / 1000000 )); R_CODE=$CODE; R_STATUS=$(echo "$BODY"|jq -r '.status // "-"'); R_REASON=$(echo "$BODY"|jq -r '.unavailableReason // "-"'); R_RETRY=$(echo "$BODY"|jq -r '.retryable // "-"'); }
attempts() { # n0 -> "N attempts; gaps_ms=[..]"
  tail -n +$(( $1 + 1 )) $ST/requests.log | python3 -c "
import sys,datetime
ts=[datetime.datetime.fromisoformat(l.split()[0].replace('Z','+00:00')) for l in sys.stdin if l.strip()]
print(len(ts),'attempt(s); gaps_ms=',[round((b-a).total_seconds()*1000) for a,b in zip(ts,ts[1:])])"; }
run() { # label failspec delay timeoutMs expect_attempts
  : > $ST/fail; [ -n "$2" ] && echo "$2" > $ST/fail; echo 0 > $ST/delay-ok.ms; case "$3" in ok:*) echo 0 > $ST/delay.ms; echo "${3#ok:}" > $ST/delay-ok.ms;; *) echo "${3:-0}" > $ST/delay.ms;; esac; local n0=$(wc -l < $ST/requests.log) a0=$(ai_rows); sleep 3.3; rect "$4"; local at=$(attempts $n0); echo "$1 -> http=$R_CODE status=$R_STATUS reason=$R_REASON retryable=$R_RETRY latency=${R_MS}ms | provider: $at | audit rows +$(( $(ai_rows) - a0 )) ($(ai_last))"; LAST_ATTEMPTS=${at%% *}
  [ -n "$5" ] && chk RES-04 "$1: $5 provider attempt(s)" "$5" "$LAST_ATTEMPTS"; echo 0 > $ST/delay.ms; echo 0 > $ST/delay-ok.ms; : > $ST/fail; local dl=${3#ok:}; [ "${dl:-0}" -gt 0 ] && sleep $(( dl / 1000 + 1 )); }  # injected handler threads outlive the client timeout: let them finish before the next case
echo "== baseline default config: max-attempts=2, timeout 15 s, backoff 250ms..2s, min-attempt-budget 1 s, SDK max-retries 0"
docker compose exec -T backend env | grep -E "DOKENE_AI_(RETRY|OPENAI_TIMEOUT|OPENAI_MAX|OPENAI_BASE)" | sort | sed 's/^/  /'
echo "== RES-03 provider faults (injected)"
run "429 Retry-After: 1 (short)" "429:1:retry-after=1" 0 "" 2
run "429 retry-after-ms: 500" "429:1:retry-after-ms=500" 0 "" 2
run "429 Retry-After: 30 (long, does not fit the 15 s budget)" "429:9:retry-after=30" 0 "" 1
run "429 without Retry-After (waits max-backoff)" "429:9" 0 "" 2
run "503 once then success" "503:1" 0 "" 2
run "503 always" "503:9" 0 "" 2
run "500 always" "500:9" 0 "" 2
run "401 (not retryable)" "401:9" 0 "" 1
run "400 (not retryable)" "400:9" 0 "" 1
run "404 (not retryable)" "404:9" 0 "" 1
echo '{"recommendation":{' > $ST/rec.json; run "malformed structured output (not retryable)" "" 0 "" 1
echo 'REFUSAL' > $ST/rec.json; run "model refusal content part (not retryable)" "" 0 "" 1
echo '{"recommendation":{"outcome":"ACTION","action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE","rationale":"Reposición habitual del café.","confidence":0.8,"draftVariables":[]}}' > $ST/rec.json
echo "== RES-02 timeout (injected delay) and RES-04 deadline"
run "provider delay 20 s, default timeout 15 s" "" 20000 "" 1
run "provider delay 10 s, request timeoutMs=3000" "" 10000 3000 1
run "503 then a 10 s delay on the second attempt, timeoutMs=4000 (second attempt gets only the remaining budget)" "503:1" ok:10000 4000 2
run "429 Retry-After: 3 with timeoutMs=3500 (hint does not leave min-attempt-budget)" "429:9:retry-after=3" 0 3500 1
run "429 Retry-After: 2 with timeoutMs=3500 (hint fits)" "429:1:retry-after=2" 0 3500 2
echo "== draft endpoint uses the same bounded retry"
: > $ST/fail; echo "503:9" > $ST/fail; n0=$(wc -l < $ST/requests.log); sleep 3.3; draft $B1 REPEAT_PURCHASE_FOLLOW_UP REPEAT_PURCHASE; echo "draft 503 always -> http=$CODE status=$(echo "$BODY"|jq -r .status) reason=$(echo "$BODY"|jq -r .unavailableReason) | provider: $(attempts $n0)"; chk RES-04 "draft 503 always: 2 provider attempts" 2 "$(attempts $n0 | cut -d' ' -f1)"; : > $ST/fail
echo "PASS=$PASSN FAIL=$FAILN"
