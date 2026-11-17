#!/usr/bin/env bash
# ABOUTME: Suite #336 HIST-05: failed and unknown outcomes. A failed callback, a callback that reconciles an unknown
# ABOUTME: outcome, and the API-only T12 resolution (role, ETag, no provider call); a late callback after T12.
#   hist05-336.sh callbacks   |   hist05-336.sh t12
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa336.sh"
assert_safe_stack false || exit 2
for u in testuser testoperator; do qa_login $u || exit 3; done
C=HIST-05; S0=$(stub_sends); N0=$(msgs_total)
case ${1:?callbacks|t12} in
callbacks)
  req testuser "$A" GET "/api/messages/$M_H5F" -
  rec $C "synchronous provider refusal (stub 401): FAILED AUTHENTICATION, no provider id, attempt FAILED_PERMANENT, no canary in the body" \
    "$([ "$(jb .status)" = FAILED ] && [ "$(jb .failureCategory)" = AUTHENTICATION ] && [ "$(jb .providerMessageId)" = null ] && ! printf '%s' "$BODY" | grep -q 'CANARY' && echo PASS || echo FAIL)" \
    "status=$(jb .status) failure=$(jb .failureCategory) attempts=$(jb '[.attempts[].outcome]|join(",")')"
  M=$M_H5W; P=$(pmid "$M"); E0=$(mevents "$M")
  wh_body "$OUTD/h05-failed.json" "$PNID_A" "$P:failed:$(date +%s):131026"
  wh_post "$OUTD/h05-failed.json"
  rec $C "signed failed callback (code 131026) on a SENT message: FAILED INVALID_RECIPIENT, one applied event, no provider request" \
    "$([ "$WCODE" = 200 ] && no_cookie && [[ $(mrow "$M") == FAILED/unknown=false/*/failure=INVALID_RECIPIENT/* ]] && [ "$(mevents "$M")" = $((E0 + 1)) ] && [ "$(attempts "$M")" = 1 ] && [ "$(stub_sends)" = "$S0" ] && echo PASS || echo FAIL)" \
    "http=$WCODE row=$(mrow "$M") events=$(mevtypes "$M") inbox=$(inbox_for "$P")"
  M=$M_H5R; P=$(pmid "$M"); E0=$(mevents "$M"); R0=$(mrow "$M")
  wh_body "$OUTD/h05-reconcile.json" "$PNID_A" "$P:delivered:$(date +%s)"
  wh_post "$OUTD/h05-reconcile.json"
  rec $C "reconciliation: a delivered callback for an unknown outcome (stub paused, id kept) -> DELIVERED, unknown cleared, no resend" \
    "$([ "$WCODE" = 200 ] && [[ $R0 == SENDING/unknown=true/* ]] && [[ $(mrow "$M") == DELIVERED/unknown=false/delivered=true/* ]] && [ "$(attempts "$M")" = 1 ] && [ "$(stub_sends)" = "$S0" ] && [ "$(msgs_total)" = "$N0" ] && echo PASS || echo FAIL)" \
    "before=$R0 after=$(mrow "$M") events=$(mevtypes "$M") attempt=$(psqlq "select outcome from dokene.outbound_send_attempts where message_id='$M'")"
  ;;
t12)
  M=$M_H5U; P=$(pmid "$M"); E0=$(mevents "$M")
  req testuser "$A" GET "/api/messages/$M" -; V=$(etag); OLD="\"0\""
  rec $C "H5U before T12: SENDING, outcomeUnknown=true, provider id kept, attempt OUTCOME_UNKNOWN" \
    "$([ "$(jb .status)" = SENDING ] && [ "$(jb .outcomeUnknown)" = true ] && [ "$(jb '[.attempts[].outcome]|join(",")')" = OUTCOME_UNKNOWN ] && echo PASS || echo FAIL)" "status=$(jb .status) unknown=$(jb .outcomeUnknown) version=$V"
  send testuser "$M"
  rec $C "a resend on the unknown outcome is refused (no blind resend)" "$([ "$CODE" = 409 ] && [ "$(stub_sends)" = "$S0" ] && [ "$(attempts "$M")" = 1 ] && echo PASS || echo FAIL)" "http=$CODE code=$(code)"
  req testoperator "$A" POST "/api/messages/$M/resolutions" '{"outcome":"FAILED","note":"QA336 operator"}' -H "If-Match: $V" -H "Idempotency-Key: $(uuidgen)"
  rec $C "T12 by OPERATOR (no INTEGRATION_MANAGE) -> 403" "$([ "$CODE" = 403 ] && echo PASS || echo FAIL)" "http=$CODE code=$(code)"
  req testuser "$A" POST "/api/messages/$M/resolutions" '{"outcome":"FAILED","note":"QA336 no if-match"}' -H "Idempotency-Key: $(uuidgen)"
  H1=$CODE; G1=$(code)
  req testuser "$A" POST "/api/messages/$M/resolutions" '{"outcome":"FAILED","note":"QA336 stale"}' -H "If-Match: $OLD" -H "Idempotency-Key: $(uuidgen)"
  rec $C "T12 by OWNER without If-Match, and with a stale ETag: both refused, message unchanged" \
    "$([ "$H1" != 200 ] && [ "$H1" != 201 ] && [ "$CODE" = 409 ] && [[ $(mrow "$M") == SENDING/unknown=true/* ]] && echo PASS || echo FAIL)" "no_if_match=$H1/$G1 stale=$CODE/$(code)"
  req testuser "$A" POST "/api/messages/$M/resolutions" '{"outcome":"FAILED","note":"QA336 checked the stub: paused, no delivery"}' -H "If-Match: $V" -H "Idempotency-Key: $(uuidgen)"
  rec $C "T12 by OWNER with the current ETag -> FAILED, unknown cleared, no provider request" \
    "$({ [ "$CODE" = 201 ] || [ "$CODE" = 200 ]; } && [ "$(jb .status)" = FAILED ] && [ "$(jb .outcomeUnknown)" = false ] && [ "$(stub_sends)" = "$S0" ] && echo PASS || echo FAIL)" \
    "http=$CODE status=$(jb .status) failure=$(jb .failureCategory) events=$(mevtypes "$M")"
  E1=$(mevents "$M"); R1=$(mrow "$M")
  wh_body "$OUTD/h05-late.json" "$PNID_A" "$P:delivered:$(date +%s)"
  wh_post "$OUTD/h05-late.json"
  rec $C "a delivered callback after T12 is acknowledged but does not reopen the FAILED message (recorded as not applied)" \
    "$([ "$WCODE" = 200 ] && [[ $(mrow "$M") == FAILED/* ]] && [ "$(psqlq "select count(*) from dokene.outbound_message_events where message_id='$M' and applied and event_type='DELIVERY_UPDATED'")" = 0 ] && [ "$(stub_sends)" = "$S0" ] && echo PASS || echo FAIL)" \
    "http=$WCODE before=$R1 after=$(mrow "$M") events_delta=$(( $(mevents "$M") - E1 )) inbox=$(inbox_for "$P")"
  ;;
esac
qa_finish
