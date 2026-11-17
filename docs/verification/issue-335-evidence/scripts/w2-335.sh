#!/usr/bin/env bash
# ABOUTME: Suite #335 window W2 (global ON, loopback stub, min contact interval 3m, recovery claim timeout 2m): SEND-06
# ABOUTME: backend crash during the provider call -> claim kept, no resend, recovery to unknown; SEND-07 contact interval.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa335.sh"
assert_safe_stack true || exit 2
stub_reset
qa_login testuser || exit 3
E=$(docker compose -f "$WT/compose.yaml" exec -T backend sh -c 'env | grep -E "^DOKENE_MESSAGING_(MIN_CONTACT_INTERVAL|RECOVERY_CLAIM_TIMEOUT|RECOVERY_INTERVAL)=" | sort | tr "\n" " "')
rec WINDOW "[W2] policy overrides in effect" "$(case "$E" in *MIN_CONTACT_INTERVAL=3m*CLAIM_TIMEOUT=2m*RECOVERY_INTERVAL=15s*) echo PASS;; *) echo FAIL;; esac)" "$E"
case "$E" in *MIN_CONTACT_INTERVAL=3m*CLAIM_TIMEOUT=2m*RECOVERY_INTERVAL=15s*) ;; *) echo "W2 overrides missing: export QA335_MIN_INTERVAL, QA335_CLAIM_TIMEOUT and QA335_RECOVERY_INTERVAL before window-open334.sh" >&2; exit 5 ;; esac
astate() { psqlq "select outcome from dokene.outbound_send_attempts where message_id='$1' order by attempt_number desc limit 1"; }

# SEND-07 first send (starts the 3-minute interval clock while the crash case runs).
C=SEND-07
S0=$(stub_sends); send testuser "$M_S7"; T7=$(date +%s)
rec $C "first message to Elsa sent (stub accepted)" "$([ "$CODE" = 201 ] && [ "$(jb .status)" = SENT ] && [ "$(stub_sends)" = $((S0 + 1)) ] && echo PASS || echo FAIL)" "http=$CODE status=$(jb .status) sentAt=$(jb .sentAt)"
due_today "$S7"; D=$CODE
mkmsg M_S7B testuser "$S7" "$S7_C" approve; MK=$?
rec $C "after the accepted terminal state a new follow-up can be scheduled, drafted and approved (no permanent block)" "$([ "$D" = 200 ] && [ $MK = 0 ] && echo PASS || echo FAIL)" "due_today http=$D new=${M_S7B:0:8} state=$(mstate "$M_S7B")"
S0=$(stub_sends); send testuser "$M_S7B"
refused CONTACT_FREQUENCY_EXCEEDED "$M_S7B" "$S0" "second message inside the 3-minute interval (http=$CODE)" $C

# SEND-06 crash: the stub never answers, the backend is killed 3 s into the provider call, then started again.
C=SEND-06
stub_mode send hang
S0=$(stub_sends)
( send testuser "$M_S6CRASH"; printf '%s %s\n' "$CODE" "$(code)" > "$OUTD/crash.http" ) &
sleep 3
A1=$(astate "$M_S6CRASH"); ST1=$(mstate "$M_S6CRASH")
rec $C "3 s into the provider call: claim committed (attempt STARTED, message SENDING)" "$([ "$A1" = STARTED ] && echo PASS || echo FAIL)" "attempt=$A1 state=$ST1"
docker compose -f "$WT/compose.yaml" kill backend >/dev/null 2>&1
wait; rec $C "backend killed during the call (fault injection)" NOTE "client saw: $(cat "$OUTD/crash.http" 2>/dev/null) at $(date -u +%T)"
stub_mode send accept
# stack335.sh rebuilds its override from the environment it is given: pass the W2 values verified above explicitly,
# so the restart keeps them even when the caller exported them only for window-open334.sh.
QA333_OUTBOUND=true QA335_MIN_INTERVAL=3m QA335_CLAIM_TIMEOUT=2m QA335_RECOVERY_INTERVAL=15s \
  bash "$STACK333" restart >&2 || { rec $C "restart after crash" FAIL "restart failed"; exit 5; }
assert_safe_stack true || exit 2
E2=$(docker compose -f "$WT/compose.yaml" exec -T backend sh -c 'env | grep -E "^DOKENE_MESSAGING_(MIN_CONTACT_INTERVAL|RECOVERY_CLAIM_TIMEOUT|RECOVERY_INTERVAL)=" | sort | tr "\n" " "')
[ "$E2" = "$E" ] || { rec $C "W2 policy overrides kept across the crash restart" FAIL "before: $E after: $E2"; exit 5; }
qa_login testuser || exit 3
S1=$(stub_sends)
req testuser "$A" GET "/api/messages/$M_S6CRASH" -
rec $C "after restart: claim kept (attempt STARTED, SENDING), no automatic resend" "$([ "$(astate "$M_S6CRASH")" = STARTED ] && [ "$(jb .status)" = SENDING ] && [ "$(attempts "$M_S6CRASH")" = 1 ] && echo PASS || echo FAIL)" "attempt=$(astate "$M_S6CRASH") status=$(jb .status) unknown=$(jb .outcomeUnknown) provider_requests_for_call=$((S1 - S0))"
send testuser "$M_S6CRASH"; H=$CODE; G=$(code)
rec $C "manual resend while the claim stands refused, no provider request" "$([ "$H" = 409 ] && [ "$(attempts "$M_S6CRASH")" = 1 ] && [ "$(stub_sends)" = "$S1" ] && echo PASS || echo FAIL)" "http=$H code=$G stub_delta=$(( $(stub_sends) - S1 ))"
# SEND-07 continued: the S6HANG customer's earlier message ended FAILED (T12); a new message may be sent at once.
C=SEND-07
due_today "$S6HANG"
mkmsg M_S6NEW testuser "$S6HANG" "$S6HANG_C" approve; MK=$?
S0=$(stub_sends); send testuser "$M_S6NEW"
rec $C "customer whose last message ended FAILED (T12): new message approved and sent at once (a failure starts no interval)" "$([ $MK = 0 ] && [ "$CODE" = 201 ] && [ "$(jb .status)" = SENT ] && [ "$(stub_sends)" = $((S0 + 1)) ] && echo PASS || echo FAIL)" "http=$CODE status=$(jb .status)"
# Wait for recovery (claim timeout 2m, sweep every 15 s) and for the 3-minute interval.
C=SEND-06
SR=$(stub_sends)   # baseline after the S6NEW send above
for _ in $(seq 1 30); do [ "$(astate "$M_S6CRASH")" = OUTCOME_UNKNOWN ] && break; sleep 10; done
qa_login testuser
req testuser "$A" GET "/api/messages/$M_S6CRASH" -
rec $C "recovery turned the stale claim into an unknown outcome without calling the provider" "$([ "$(astate "$M_S6CRASH")" = OUTCOME_UNKNOWN ] && [ "$(jb .outcomeUnknown)" = true ] && [ "$(attempts "$M_S6CRASH")" = 1 ] && [ "$(stub_sends)" = "$SR" ] && echo PASS || echo FAIL)" "attempt=$(astate "$M_S6CRASH") status=$(jb .status) unknown=$(jb .outcomeUnknown) events=$(events "$M_S6CRASH") $(psqlq "select 'started='||to_char(started_at,'HH24:MI:SS')||' finished='||to_char(finished_at,'HH24:MI:SS') from dokene.outbound_send_attempts where message_id='$M_S6CRASH'")"
S2=$(stub_sends); send testuser "$M_S6CRASH"
rec $C "resend after recovery refused (refresh or T12 only), no provider request" "$([ "$(code)" = INVALID_TRANSITION ] && [ "$(stub_sends)" = "$S2" ] && echo PASS || echo FAIL)" "http=$CODE code=$(code)"
req testuser "$A" GET "/api/messages/$M_S6CRASH" -
req testuser "$A" POST "/api/messages/$M_S6CRASH/resolutions" '{"outcome":"FAILED","note":"QA335 crash case: stub never answered"}' -H "If-Match: $(etag)" -H "Idempotency-Key: $(uuidgen)"
rec $C "T12 resolution (API, OWNER) of the recovered claim: FAILED, no provider request" "$([ "$(jb .status)" = FAILED ] && [ "$(stub_sends)" = "$S2" ] && echo PASS || echo FAIL)" "http=$CODE status=$(jb .status) events=$(events "$M_S6CRASH")"
C=SEND-07
W=$(( T7 + 185 - $(date +%s) )); [ $W -gt 0 ] && sleep $W
qa_login testuser
S0=$(stub_sends); send testuser "$M_S7B"
rec $C "same approved second message after the 3-minute interval: sent (no premature send, no permanent block)" "$([ "$CODE" = 201 ] && [ "$(jb .status)" = SENT ] && [ "$(stub_sends)" = $((S0 + 1)) ] && [ "$(attempts "$M_S7B")" = 1 ] && echo PASS || echo FAIL)" "http=$CODE status=$(jb .status) seconds_after_first=$(( $(date +%s) - T7 )) events=$(events "$M_S7B")"
qa_finish
