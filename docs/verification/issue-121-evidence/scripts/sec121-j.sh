#!/usr/bin/env bash
# QA suite #121: SEC-08 secret / data minimization. Provider = stub for payload capture and error propagation (FAULT INJECTION); canary markers are synthetic.
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa121/s/env121.sh
reqs() { wc -l < $STUB/requests.log; }; echo 0 > $STUB/delay.ms; : > $STUB/fail
echo '{"recommendation":{"outcome":"ACTION","action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE","rationale":"Canary rationale CANARY-RAT-91.","confidence":0.8,"draftVariables":[]}}' > $STUB/rec.json
echo '{"draft":{"outcome":"DRAFT","action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE","body":"Hola, ¿cómo te fue con el Café CANARY-BODY-77 que compraste?","draftVariables":[],"locale":"es-419","evidence":["Compra: Café CANARY-BUY-55"],"warnings":[],"rationale":"Reposición.","confidence":0.9}}' > $STUB/draft.json
PH=$(rnd_phone); CN=$(mk "Canary Nombre CANARY-NAME-33" "$PH" "Nota privada CANARY-NOTE-12 contacto interno"); consent $CN GRANTED >/dev/null; buy $CN 2026-08-01T14:00:00Z "Café CANARY-BUY-55" >/dev/null
DIGITS=${PH#+}; n0=$(reqs); rec $CN; draft $CN REPEAT_PURCHASE_FOLLOW_UP REPEAT_PURCHASE; echo "  recommendation/draft status: $(echo "$REC_BODY"|jq -r .status) / $(echo "$BODY"|jq -r .status)  provider requests: $(( $(reqs) - n0 ))"
echo "== what the provider receives (captured by the stub)"
for k in rec draft; do f=$STUB/last-$k.json
  chk SEC-08 "$k request: no phone number (E.164 or digits)" 0 "$(grep -c -e "$PH" -e "$DIGITS" $f)"
  chk SEC-08 "$k request: no customer/tenant/membership UUID" 0 "$(grep -Eoi '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' $f | grep -v 'resp_' | wc -l)"
  chk SEC-08 "$k request: no email address" 0 "$(grep -Eo '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[a-z]{2,}' $f | wc -l)"
  chk SEC-08 "$k request: store=false (no provider-side retention)" false "$(jq -r .store $f)"
  chk SEC-08 "$k request: no end-user identifier (user field) sent" null "$(jq -r .user $f)"
  echo "  $k request: customer name sent=$(grep -c CANARY-NAME-33 $f)  notes sent=$(grep -c CANARY-NOTE-12 $f)  (observation: what business data reaches the provider)"
  echo "  $k request keys: $(jq -c 'keys' $f)"; done
echo "== server logs (backend container) must not contain raw content"
LOG=/home/stevdrey/qa121/out/backend.log; docker compose logs --no-log-prefix backend > $LOG 2>&1; echo "  log lines: $(wc -l < $LOG)"
KEY="$DOKENE_AI_OPENAI_API_KEY"
for pat in CANARY-NAME-33 CANARY-NOTE-12 CANARY-BUY-55 CANARY-RAT-91 CANARY-BODY-77 "$PH" "$DIGITS"; do chk SEC-08 "logs contain no '${pat:0:16}'" 0 "$(grep -c -F -- "$pat" $LOG)"; done
[ -n "$KEY" ] && chk SEC-08 "logs contain no provider API key" 0 "$(grep -c -F -- "$KEY" $LOG)"
chk SEC-08 "logs contain no session cookie / CSRF token of the test sessions" 0 "$(grep -c -F -- "$(curl -s -b $QA_TMP/jar-testoperator.txt $BFF/api/session | jq -r .csrfToken)" $LOG)"
echo "== audit rows and DB (no raw AI content)"
chk SEC-08 "audit_events contain no canary marker or phone" 0 "$(psqlq "select count(*) from dokene.audit_events a where a::text like '%CANARY-%' or a::text like '%$DIGITS%'")"
echo "  AI audit row columns populated for the last invocation: $(psqlq "select concat_ws(',',event_type,ai_operation,ai_outcome,ai_detail,outcome) from dokene.audit_events where event_type='AI_INVOCATION_OUTCOME' order by occurred_at desc limit 1")"
echo "  audit_events columns: $(psqlq "select string_agg(column_name,',' order by ordinal_position) from information_schema.columns where table_schema='dokene' and table_name='audit_events'")"
chk SEC-08 "no table stores generated bodies (canary body/rationale absent from the whole database dump)" 0 "$(docker compose exec -T postgres sh -c 'pg_dump -U $POSTGRES_USER -d $POSTGRES_DB --data-only' | grep -c -e CANARY-BODY-77 -e CANARY-RAT-91)"
echo "== client-visible errors never carry provider text"
for st in 401 429 500 503; do echo "$st:6" > $STUB/fail; sleep 3.2; rec $CN; txt="$REC_BODY $REC_HDRS"; echo "  provider $st -> http=$REC_CODE status=$(echo "$REC_BODY"|jq -r '.status // "-"') reason=$(echo "$REC_BODY"|jq -r '.unavailableReason // "-"') retryable=$(echo "$REC_BODY"|jq -r '.retryable // "-"')"
  chk SEC-08 "provider $st: client response has no provider message/stub text" 0 "$(echo "$txt" | grep -ci -e 'stub failure' -e 'server_error' -e 'openai' -e 'exception' -e 'stack')"; done; : > $STUB/fail
echo "== frontend bundle and browser storage"
BASEJS=$(curl -s http://localhost:5173/ | grep -o 'assets/[^"]*\.js' | head -5); echo "  bundle files: $(echo $BASEJS | wc -w)"
: > /home/stevdrey/qa121/out/bundle.txt; for f in $BASEJS; do curl -s http://localhost:5173/$f >> /home/stevdrey/qa121/out/bundle.txt; done
chk SEC-08 "frontend bundle contains no api.openai.com / openai SDK / sk- key" 0 "$(grep -c -i -E 'api\.openai\.com|openai_api_key|"sk-[A-Za-z0-9]{20,}' /home/stevdrey/qa121/out/bundle.txt)"
[ -n "$KEY" ] && chk SEC-08 "frontend bundle does not contain the provider key" 0 "$(grep -c -F -- "$KEY" /home/stevdrey/qa121/out/bundle.txt)"
chk SEC-08 "no actuator/metrics endpoint is reachable from the BFF" yes "$(for p in /actuator /actuator/metrics /actuator/prometheus /actuator/health; do c=$(curl -s -o /dev/null -w '%{http_code}' $BFF$p); [ "$c" = 200 ] && echo -n "$p=200 "; done | grep -q . && echo no || echo yes)"
echo "PASS=$PASSN FAIL=$FAILN"
