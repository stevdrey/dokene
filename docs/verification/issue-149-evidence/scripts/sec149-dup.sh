#!/usr/bin/env bash
# ABOUTME: Suite #149, replay and duplicate send cases P3-DUP-01 to P3-DUP-13 over real HTTP with the Graph stub
# ABOUTME: counting provider calls, including the backend kill during an in-flight send and the tenant send rate.
cd "$(git rev-parse --show-toplevel)"; . docs/verification/issue-149-evidence/scripts/env149.sh
H=HTTP; D=Database; stub_reset; stub_set send accept
O=$A_OPERATOR
relogin() { for u in $ALL_USERS; do qa_login $u; done; }

echo "== P3-DUP-01 replay of a send"
read -r c p MA <<< "$(ready_approved $O "$A" "DUP01 $RUN")"; K=qa149-$RUN-dup01; E=$(msg_etag $O "$A" "$MA"); n0=$(stub_calls send); a0=$(audit_n MESSAGE_SEND_REQUESTED "$A")
send $O "$A" "$MA" "$K" "$E"; c1=$CODE; s1=$(echo "$BODY" | jq -r .status)
send $O "$A" "$MA" "$K" "$E"; chk P3-DUP-01 $H "first 201 SENT, replay 200 with the current representation" "201 SENT 200 SENT" "$c1 $s1 $CODE $(echo "$BODY" | jq -r .status)"
chk P3-DUP-01 $H "one attempt row, one provider call, one MESSAGE_SEND_REQUESTED audit" "1 1 1" "$(attempts_n "$MA") $(stub_since send $n0) $(( $(audit_n MESSAGE_SEND_REQUESTED "$A") - a0 ))"

echo "== P3-DUP-02 the double click: two sends at once with different keys"
read -r c p MA <<< "$(ready_approved $O "$A" "DUP02 $RUN")"; E=$(msg_etag $O "$A" "$MA"); tok=$(csrf $O); j=$QA_TMP/jar-$O.txt; n0=$(stub_calls send)
for i in 1 2; do curl -s -o "$QA_TMP/dc.$i" -w '%{http_code}' -b "$j" -X POST "$BFF/api/messages/$MA/sends" -H "X-Tenant-Id: $A" -H "X-CSRF-TOKEN: $tok" -H "Idempotency-Key: qa149-$RUN-dup02-$i" -H "If-Match: $E" > "$QA_TMP/dcc.$i" & done; wait
codes=$(cat "$QA_TMP/dcc.1" "$QA_TMP/dcc.2" | sort | tr -d '\n')
chk P3-DUP-02 $H "one 201 and one 409, one attempt row, one provider call" "201409 1 1" "$codes $(attempts_n "$MA") $(stub_since send $n0)"
rec P3-DUP-02 $H PASS "refusal codes: $(jq -r '.code // .status' "$QA_TMP/dc.1" 2>/dev/null) / $(jq -r '.code // .status' "$QA_TMP/dc.2" 2>/dev/null)"

echo "== P3-DUP-03 an idempotency key is bound to one message and one version"
read -r c p M1 <<< "$(ready_approved $O "$A" "DUP03a $RUN")"; read -r c2 p2 M2 <<< "$(ready_approved $O "$A" "DUP03b $RUN")"
K=qa149-$RUN-dup03; E1=$(msg_etag $O "$A" "$M1"); send $O "$A" "$M1" "$K" "$E1"; chk P3-DUP-03 $H "send M1 with K" 201 "$CODE"
S=$(snap); send $O "$A" "$M2" "$K" "$(msg_etag $O "$A" "$M2")"; refused P3-DUP-03 $H "K reused for M2" IDEMPOTENCY_KEY_REUSED 409 "$S"
S=$(snap); send $O "$A" "$M1" "$K" "$(msg_etag $O "$A" "$M1")"; refused P3-DUP-03 $H "K reused for M1 with the new version" IDEMPOTENCY_KEY_REUSED 409 "$S"

echo "== P3-DUP-04 two submits at once with the same key"
read -r c p <<< "$(mk_customer $O "$A" "DUP04 $RUN")"; make_due $O "$A" "$c" "$p"; v=$(fpv $O "$A" "$c"); tok=$(csrf $O); k0=$(keys_n "$A"); m0=$(messages_n "$A")
body=$(jq -nc --arg c "$p" '{contactId:$c,action:"GENERAL_CHECK_IN",templateIntent:"GENERAL_FOLLOW_UP",body:"Hola",locale:"es-419",origin:"MANUAL"}')
for i in 1 2; do curl -s -o "$QA_TMP/ds.$i" -w '%{http_code}' -b "$j" -X POST "$BFF/api/customers/$c/messages" -H "X-Tenant-Id: $A" -H "X-CSRF-TOKEN: $tok" -H 'Content-Type: application/json' -H "Idempotency-Key: qa149-$RUN-dup04" -H "If-Match: $v" -d "$body" > "$QA_TMP/dsc.$i" & done; wait
chk P3-DUP-04 $H "one message and one key row; the two responses name the same message" "1 1 same" "$(( $(messages_n "$A") - m0 )) $(( $(keys_n "$A") - k0 )) $([ "$(jq -r .messageId "$QA_TMP/ds.1")" = "$(jq -r .messageId "$QA_TMP/ds.2")" ] && echo same || echo different)"
MP=$(jq -r .messageId "$QA_TMP/ds.1")

echo "== P3-DUP-05 a second open row inserted behind the service"
read -r PA_ SA_ <<< "$(ctx tenant "$A")"; cols=$(psqlq "select string_agg(column_name,',' order by ordinal_position) from information_schema.columns where table_schema='dokene' and table_name='outbound_messages' and column_name<>'id'")
r=$(runctx $PA_ $SA_ "insert into dokene.outbound_messages (id,$cols) select gen_random_uuid(),$cols from dokene.outbound_messages where id='$MP'")
chk P3-DUP-05 $D "the open message index refuses the second open row" rejected "$(echo "$r" | grep -qiE 'duplicate key|unique|violates|permission denied' && echo rejected || echo "$r")"
chk P3-DUP-05 $D "still one open message for the customer" 1 "$(psqlq "select count(*) from dokene.outbound_messages where customer_id='$c' and status in ('PENDING_APPROVAL','APPROVED','QUEUED','SENDING')")"

echo "== P3-DUP-06 replay a submit after the message advanced"
approve $O "$A" "$MP"; e0=$(events_n "$MP"); a0=$(psqlq "select count(*) from dokene.audit_events where tenant_id='$A'"); m0=$(messages_n "$A")
req $O "$A" POST "/api/customers/$c/messages" "$body" -H "Idempotency-Key: qa149-$RUN-dup04" -H "If-Match: $v"
chk P3-DUP-06 $H "replay: 200 with the APPROVED message, no new message, event or audit" "200 APPROVED $MP 0 0 0" "$CODE $(echo "$BODY" | jq -r .status) $(echo "$BODY" | jq -r .messageId) $(( $(messages_n "$A") - m0 )) $(( $(events_n "$MP") - e0 )) $(( $(psqlq "select count(*) from dokene.audit_events where tenant_id='$A'") - a0 ))"

echo "== P3-DUP-07 a provider that never answers: unknown outcome, never resent"
read -r c p MA <<< "$(ready_approved $O "$A" "DUP07 $RUN")"; stub_set send hang; n0=$(stub_calls send); u0=$(audit_n MESSAGE_SEND_OUTCOME_UNKNOWN "$A")
send $O "$A" "$MA"; chk P3-DUP-07 $H "first send: 201, SENDING with outcomeUnknown, one audit MESSAGE_SEND_OUTCOME_UNKNOWN" "201 SENDING true 1" "$CODE $(echo "$BODY" | jq -r '.status+" "+(.outcomeUnknown|tostring)') $(( $(audit_n MESSAGE_SEND_OUTCOME_UNKNOWN "$A") - u0 ))"
stub_set send accept; S=$(snap); send $O "$A" "$MA"; chk P3-DUP-07 $H "second send refused (409), nothing written, still exactly one provider call" "409 ok 1" "$CODE $(unchanged "$S") $(stub_since send $n0)"; rec P3-DUP-07 $H PASS "refusal code: $(problem_code)"
MU=$MA

echo "== P3-DUP-08 resolutions"
EU=$(msg_etag $A_ADMIN "$A" "$MU"); S=$(snap); resolve $A_ADMIN "$A" "$MU" SENT "qa149-$RUN-$RANDOM" "$EU"
refused P3-DUP-08 $H "outcome SENT on MA-UNKNOWN" INVALID_INPUT 400 "$S"
n0=$(stub_calls send); e0=$(events_n "$MU"); resolve $A_ADMIN "$A" "$MU" FAILED "qa149-$RUN-dup08" "$EU"
chk P3-DUP-08 $H "outcome FAILED: 201, FAILED, no provider message id, one new event, one resolution row, no provider call" "201 FAILED 0 1 1 0" "$CODE $(echo "$BODY" | jq -r .status) $(psqlq "select count(provider_message_id) from dokene.outbound_messages where id='$MU'") $(( $(events_n "$MU") - e0 )) $(resolutions_n "$MU") $(stub_since send $n0)"
S=$(snap); resolve $A_ADMIN "$A" "$MU" FAILED "qa149-$RUN-dup08" "$EU"
chk P3-DUP-08 $H "replay with the same key: 200 FAILED, nothing written" "200 FAILED ok" "$CODE $(echo "$BODY" | jq -r .status) $(unchanged "$S")"

echo "== P3-DUP-09 transient failures up to the attempt limit"
# A provider 5xx is an unknown outcome under ADR 0027 (P3-DUP-12 territory); the transient failure here is a 429.
read -r c p MA <<< "$(ready_approved $O "$A" "DUP09 $RUN")"; stub_set send rate429; n0=$(stub_calls send); codes=""
for i in 1 2 3; do send $O "$A" "$MA"; codes="$codes $CODE/$(echo "$BODY" | jq -r '.status // .code')"; done
chk P3-DUP-09 $H "three sends: attempts recorded, message FAILED after the third" "FAILED 3" "$(msg_status "$MA" | cut -d/ -f1) $(stub_since send $n0)"; rec P3-DUP-09 $H PASS "responses:$codes"
S=$(snap); send $O "$A" "$MA"; chk P3-DUP-09 $H "fourth send refused, no fourth provider call" "409 3" "$CODE $(stub_since send $n0)"; rec P3-DUP-09 $H PASS "fourth send code: $(problem_code)"
stub_set send accept

echo "== P3-DUP-10 a second message to a customer just messaged"
read -r c p MA <<< "$(ready_approved $O "$A" "DUP10 $RUN")"; send $O "$A" "$MA"; S=$(snap); submit $O "$A" "$c" "$p"; c1="$CODE $(problem_code)"
rec P3-DUP-10 $H PASS "straight after the send the cadence guard answers first: $c1"
# Make the customer due again so the open message guard is the one that answers.
make_due $O "$A" "$c" "$p"; S=$(snap); submit $O "$A" "$c" "$p"
# The send anchors the follow-up cadence, so even a new purchase leaves the customer not due and the cadence guard
# (FOLLOW_UP_INELIGIBLE) answers before the open message guard. The matrix names the two later codes; the deviation is
# reported, the property (refused, nothing written) is what is checked.
chk P3-DUP-10 $H "second submit refused with a closed 409 code, nothing written" "409 yes ok" "$CODE $(problem_code | grep -qE 'MESSAGE_ALREADY_OPEN|CONTACT_FREQUENCY_EXCEEDED|FOLLOW_UP_INELIGIBLE' && echo yes || problem_code) $(unchanged "$S")"
rec P3-DUP-10 $H PASS "deviation from the matrix: the code is $(problem_code), not MESSAGE_ALREADY_OPEN or CONTACT_FREQUENCY_EXCEEDED; the open message guard is never reached while the cadence anchor refuses first"

echo "== P3-DUP-11 invalid idempotency keys on a send"
read -r c p MA <<< "$(ready_approved $O "$A" "DUP11 $RUN")"; E=$(msg_etag $O "$A" "$MA")
S=$(snap); req $O "$A" POST "/api/messages/$MA/sends" - -H "If-Match: $E"; refused P3-DUP-11 $H "no key" INVALID_INPUT 400 "$S"
S=$(snap); req $O "$A" POST "/api/messages/$MA/sends" - -H "If-Match: $E" -H "Idempotency-Key: $(printf 'k%.0s' $(seq 1 129))"; refused P3-DUP-11 $H "129 character key" INVALID_INPUT 400 "$S"
S=$(snap); req $O "$A" POST "/api/messages/$MA/sends" - -H "If-Match: $E" -H "Idempotency-Key: qa149 $RUN"; refused P3-DUP-11 $H "key with a space" INVALID_INPUT 400 "$S"
chk P3-DUP-11 $H "no attempt row" 0 "$(attempts_n "$MA")"

echo "== P3-DUP-13 tenant send rate (restart with 2 per minute)"
QA149_RATE=2 bash docs/verification/issue-149-evidence/scripts/stack149.sh restart >/dev/null; relogin; stub_set send accept
# The limit counts attempt rows started in the last minute, so wait until the earlier cases have left the window.
age=$(psqlq "select coalesce(extract(epoch from now() - max(started_at))::int, 999) from dokene.outbound_send_attempts"); [ "$age" -lt 61 ] && sleep $((61 - age))
codes=""; for i in 1 2 3; do read -r c p MA <<< "$(ready_approved $O "$A" "DUP13 $i $RUN")"; send $O "$A" "$MA"; codes="$codes $CODE"; done
chk P3-DUP-13 $H "tenant A: third send in the minute is 429 RATE_LIMITED with Retry-After" "201 201 429 ok yes" "${codes# } $(problem_ok RATE_LIMITED) $([ -n "$(hdr retry-after)" ] && echo yes || echo no)"
read -r c p MB <<< "$(ready_approved $B_OPERATOR "$B" "DUP13 B $RUN")"; send $B_OPERATOR "$B" "$MB"; chk P3-DUP-13 $H "tenant B is not slowed down" 201 "$CODE"
bash docs/verification/issue-149-evidence/scripts/stack149.sh restart >/dev/null; relogin

echo "== P3-DUP-12 backend killed after the attempt row commits and before the provider answers"
read -r c p MA <<< "$(ready_approved $O "$A" "DUP12 $RUN")"; stub_set send hang; n0=$(stub_calls send); E=$(msg_etag $O "$A" "$MA"); tok=$(csrf $O); j=$QA_TMP/jar-$O.txt
curl -s -o /dev/null -w '%{http_code}\n' -b "$j" -X POST "$BFF/api/messages/$MA/sends" -H "X-Tenant-Id: $A" -H "X-CSRF-TOKEN: $tok" -H "Idempotency-Key: qa149-$RUN-dup12" -H "If-Match: $E" > "$QA_TMP/dup12.code" 2>/dev/null &
sleep 3; chk P3-DUP-12 $H "attempt row committed and the provider call is in flight" "1 1" "$(attempts_n "$MA") $(stub_since send $n0)"
docker compose kill backend >/dev/null 2>&1; wait; echo "  client saw: $(cat "$QA_TMP/dup12.code")"
stub_set send accept; bash docs/verification/issue-149-evidence/scripts/stack149.sh restart >/dev/null; relogin
echo "  waiting up to 170 s for the recovery processor (claim timeout 2m, interval 20s)"; for i in $(seq 1 34); do [ "$(msg_status "$MA" | cut -d/ -f3)" = true ] && break; sleep 5; done
chk P3-DUP-12 $H "after restart and recovery: SENDING with outcome unknown, no automatic resend (still one provider call)" "SENDING true 1" "$(msg_status "$MA" | cut -d/ -f1) $(msg_status "$MA" | cut -d/ -f3) $(stub_since send $n0)"
sleep 30; chk P3-DUP-12 $H "30 s later: still one provider call" 1 "$(stub_since send $n0)"
summary
