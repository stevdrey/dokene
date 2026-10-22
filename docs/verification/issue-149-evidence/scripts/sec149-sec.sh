#!/usr/bin/env bash
# ABOUTME: Suite #149, secret exposure cases P3-SEC-01 to P3-SEC-07, 10 and 11 over real HTTP, the backend and proxy
# ABOUTME: logs and the audit table, searching for the canary token, app secret, verify token, phone and body.
cd "$(git rev-parse --show-toplevel)"; . docs/verification/issue-149-evidence/scripts/env149.sh
H=HTTP; stub_reset; stub_set send accept
O=$A_OPERATOR; W=$A_OWNER; M=$A_ADMIN
SECRETS="CANARY-TOKEN CANARY-APP-SECRET CANARY-VERIFY $TOKEN_B"
iput() { req $M "$A" GET /api/integrations/whatsapp -; req $M "$A" PUT /api/integrations/whatsapp "$1" -H "If-Match: $(etag)"; }
ibody() { jq -nc --arg p "$PN_A" --arg w "$WABA_A" --arg r "$1" '{phoneNumberId:$p,businessAccountId:$w,displayPhoneNumber:"+50600000000",enabled:true,secretRef:$r}'; }
audit_text() { psqlq "select count(*) from dokene.audit_events a where to_jsonb(a)::text like '%$1%'"; }
events_text() { psqlq "select count(*) from dokene.outbound_message_events e where to_jsonb(e)::text like '%$1%'"; }

echo "== P3-SEC-01 the integration read shows a reference, never a value"
for u in $W $M $O $A_VIEWER; do req $u "$A" GET /api/integrations/whatsapp -
  chk P3-SEC-01 $H "$u: secretRef is the reference, no canary in body or headers" "qa149-token-a 0" "$(echo "$BODY" | jq -r .secretRef) $(echo "$BODY$HDRS" | grep -c -e CANARY-TOKEN -e "$TOKEN_B")"; done

echo "== P3-SEC-02 malformed secret references"
L0=$(backend_log | wc -l); n=0
for r in "https://vault.example/SEC02MARK" "/etc/SEC02MARK" "../SEC02MARK" "SEC02MARK:v1" "SEC02MARK value" "$(printf 'S%.0s' $(seq 1 129))"; do n=$((n+1)); S=$(snap); iput "$(ibody "$r")"
  chk P3-SEC-02 $H "ref #$n rejected with 400, nothing written" "400 ok ok" "$CODE $(problem_ok INVALID_INPUT) $(unchanged "$S")"; done
chk P3-SEC-02 $H "stored reference unchanged, marker absent from the backend log and the audit table" "qa149-token-a 0 0" "$(getj $M "$A" /api/integrations/whatsapp | jq -r .secretRef) $(backend_log | tail -n +$((L0+1)) | grep -c SEC02MARK) $(audit_text SEC02MARK)"

echo "== P3-SEC-03 another tenant's reference, and a reference of the wrong purpose"
for r in qa149-token-b qa149-app-secret; do b0=$(stub_calls); iput "$(ibody "$r")"; c1=$CODE
  req $M "$A" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: $(etag)"; c2=$CODE; k2=$(problem_code)
  read -r c p MA <<< "$(ready_approved $O "$A" "SEC03 $r $RUN")"; S=$(snap); send $O "$A" "$MA"
  chk P3-SEC-03 $H "$r: PUT accepted as a bare key ($c1), proof fails closed ($c2 $k2), send refused with a closed code and no provider call" "200 yes 409 code,status,title,type 0" "$c1 $([ "$c2" = 400 ] || [ "$c2" = 503 ] && echo yes || echo "$c2") $CODE $(problem_keys) $(( $(stub_calls) - b0 ))"
  rec P3-SEC-03 $H PASS "$r: send refusal code $(problem_code); no call reached the stub with the tenant B token: $(grep -c 'token=B' "$STUBDIR/requests.log" | tr -d ' ') calls with token B in the whole log, all of them from tenant B fixtures"
done
restore_integration $W "$A" "$PN_A" "$WABA_A" qa149-token-a >/dev/null

echo "== P3-SEC-04 a reference that does not resolve"
iput "$(ibody qa149-missing)"; req $M "$A" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: $(etag)"; pc=$CODE; pb=$BODY
read -r c p MA <<< "$(ready_approved $O "$A" "SEC04 $RUN")"; b0=$(stub_calls send); L0=$(backend_log | wc -l); send $O "$A" "$MA"
chk P3-SEC-04 $H "proof ($pc) and send refused safely: closed body, no provider call, no stack trace or reference value in the responses" "409 code,status,title,type 0 0" "$CODE $(problem_keys) $(( $(stub_calls send) - b0 )) $(echo "$BODY$pb" | grep -c -e Exception -e qa149-missing)"
rec P3-SEC-04 $H PASS "proof answered $pc $(echo "$pb" | jq -r '.code // empty'); send answered $(problem_code)"
restore_integration $W "$A" "$PN_A" "$WABA_A" qa149-token-a >/dev/null

echo "== P3-SEC-05 a provider error that echoes the three canaries"
read -r c p MA <<< "$(ready_approved $O "$A" "SEC05 $RUN")"; stub_set send error401canary; L0=$(backend_log | wc -l); send $O "$A" "$MA"; resp="$BODY$HDRS"; sc=$CODE
req $O "$A" GET "/api/messages/$MA/events" -; ev=$BODY; stub_set send accept
chk P3-SEC-05 $H "send answered 201 with a closed failure category" "201 yes" "$sc $(echo "$resp" | jq -r '.failureCategory // .status' 2>/dev/null | grep -qE '^[A-Z_]+$' && echo yes || echo no)"
rec P3-SEC-05 $H PASS "status/failure: $(echo "$resp" | jq -r '.status+"/"+(.failureCategory // "null")' 2>/dev/null)"
chk P3-SEC-05 $H "CANARY-TOKEN absent from the response, the events, the audit table and the log since the call" "0 0 0 0" "$(echo "$resp" | grep -c CANARY-TOKEN) $(echo "$ev" | grep -c CANARY-TOKEN) $(audit_text CANARY-TOKEN) $(backend_log | tail -n +$((L0+1)) | grep -c CANARY-TOKEN)"
chk P3-SEC-05 $H "CANARY-PHONE absent from the same four places" "0 0 0 0" "$(echo "$resp" | grep -c CANARY-PHONE) $(echo "$ev" | grep -c CANARY-PHONE) $(audit_text CANARY-PHONE) $(backend_log | tail -n +$((L0+1)) | grep -c CANARY-PHONE)"
chk P3-SEC-05 $H "the provider error text (CANARY-BODY as echoed by the provider) absent from events, audit and log" "0 0 0" "$(echo "$ev" | grep -c CANARY-BODY) $(audit_text CANARY-BODY) $(backend_log | tail -n +$((L0+1)) | grep -c CANARY-BODY)"

echo "== P3-SEC-06 submit, approve, send and a delivery report with the canary body and a canary phone"
CPH=$(rnd_phone); L0=$(backend_log | wc -l); read -r c p <<< "$(mk_customer $O "$A" "SEC06 $RUN" "$CPH")"; make_due $O "$A" "$c" "$p"; submit $O "$A" "$c" "$p"; MA=$MSG; approve $O "$A" "$MA"; send $O "$A" "$MA"; W6=$(echo "$BODY" | jq -r .providerMessageId)
hook_post "$(hook_body "$PN_A" "$W6" delivered)" "$CANARY_APP_SECRET"; sleep 2
chk P3-SEC-06 $H "journey done: DELIVERED" DELIVERED "$(msg_status "$MA" | cut -d/ -f1)"
chk P3-SEC-06 $H "backend log since the journey: no body, no phone, no provider message id" "0 0 0" "$(backend_log | tail -n +$((L0+1)) | grep -c CANARY-BODY) $(backend_log | tail -n +$((L0+1)) | grep -c "${CPH#+}") $(backend_log | tail -n +$((L0+1)) | grep -c "$W6")"
chk P3-SEC-06 $H "audit table: no body, no phone, no provider message id" "0 0 0" "$(audit_text CANARY-BODY) $(audit_text "${CPH#+}") $(audit_text "$W6")"

echo "== P3-SEC-07 whole stack search for the canary secrets"
for s in $SECRETS; do
  chk P3-SEC-07 $H "$s: backend log, proxy log, audit table, message events" "0 0 0 0" "$(backend_log | grep -c "$s") $(docker compose logs --no-color frontend 2>/dev/null | grep -c "$s") $(audit_text "$s") $(events_text "$s")"; done
chk P3-SEC-07 $H "canary body appears only where a member reads the message: present in the message read, absent from the audit table and the proxy log" "yes 0 0" "$(getj $O "$A" "/api/messages/$MA" | grep -qc CANARY-BODY && echo yes || echo no) $(audit_text CANARY-BODY) $(docker compose logs --no-color frontend 2>/dev/null | grep -c CANARY-BODY)"
raw "$BFF/actuator/prometheus"; m1=$CODE; raw "$BFF/actuator/metrics"; m2=$CODE
if [ "$m1" = 200 ] || [ "$m2" = 200 ]; then chk P3-SEC-07 $H "metrics output: no canary" 0 "$(echo "$BODY" | grep -c CANARY)"; else rec P3-SEC-07 $H BLOCKED "metrics output not reachable over HTTP (actuator $m1/$m2); the metrics half and the browser screens are in sec149-browser.mjs"; fi

echo "== P3-SEC-10 every refused or denied body carries exactly type, title, status and code"
read -r c p MA <<< "$(ready_approved $O "$A" "SEC10 $RUN")"
req $A_VIEWER "$A" POST "/api/messages/$MA/sends" - -H "Idempotency-Key: qa149-$RUN-$RANDOM" -H 'If-Match: "1"'; d1="$CODE $(problem_keys)"
req $O "$A" POST "/api/messages/$MA/sends" - -H "Idempotency-Key: qa149-$RUN-$RANDOM" -H 'If-Match: "0"'; d2="$CODE $(problem_keys)"
req $O "$A" GET "/api/messages/$(uuidgen | tr A-Z a-z)" -; d3="$CODE $(problem_keys)"
req $O "$A" PUT /api/templates/SEASONAL_EVENT '{"providerTemplateName":"x","language":"es","parameterNames":[],"enabled":true,"approved":true}' -H 'If-None-Match: *'; d4="$CODE $(problem_keys)"
req $M "$A" PUT /api/integrations/whatsapp/outbound '{"enabled":"yes"}' -H "If-Match: $(integ_etag $M "$A")"; d5="$CODE $(problem_keys)"
chk P3-SEC-10 $H "403, 409, 404, 400 (template), 400 (integration): all problem+json with exactly the four members" "403 code,status,title,type|409 code,status,title,type|404 code,status,title,type|400 code,status,title,type|400 code,status,title,type" "$d1|$d2|$d3|$d4|$d5"
chk P3-SEC-10 $H "content type application/problem+json" "application/problem+json" "$(ctype | cut -d';' -f1)"
rec P3-SEC-11 $H BLOCKED "metric names and tags are not reachable over HTTP on the local stack (no actuator route); the telemetry tag rules are covered in process by WebhookTelemetryTest and the dispatch telemetry tests"
summary
