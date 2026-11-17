#!/usr/bin/env bash
# ABOUTME: Suite #334 FLOW-04 API probes: stored message content cannot be edited, terminal statuses refuse further
# ABOUTME: a pending message can be cancelled, and no provider side effect occurs. Terminal-state refusals: flow04-transitions334.sh.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa334.sh"; [ -f "$OUTD/ids" ] && . "$OUTD/ids"
C=FLOW-04
qa_login testuser
st() { req testuser "$A" GET /api/messages/$1 -; printf '%s' "$BODY" | jq -r .status; }
body() { req testuser "$A" GET /api/messages/$1 -; printf '%s' "$BODY" | jq -r .body; }
req testuser "$A" GET /api/messages/$M1 -; B1=$(printf '%s' "$BODY" | jq -r .body); S1=$(printf '%s' "$BODY" | jq -r .status); V1=$(etag)
rec $C "M1 (approved, then Corregir) is CANCELLED and its stored body is the original reviewed text" $([ "$S1" = CANCELLED ] && [ "${B1%Saludos del equipo QA334.}" != "$B1" ] && echo PASS || echo FAIL) "status=$S1 bodyEndsWithOriginal=$([ "${B1%Saludos del equipo QA334.}" != "$B1" ] && echo yes)"
for m in PUT PATCH; do
  req testuser "$A" $m /api/messages/$M1 '{"body":"QA334 tampered"}' -H "If-Match: $V1"
  rec $C "$m /api/messages/{id} (edit stored content) is not a route" $(case $CODE in 404|405) echo PASS;; *) echo FAIL;; esac) "http=$CODE code=$(printf '%s' "$BODY" | jq -r '.code? // empty' 2>/dev/null)"
done
rec $C "M1 body unchanged after edit attempts" $([ "$(body "$M1")" = "$B1" ] && echo PASS || echo FAIL) "-"
req testuser "$A" GET /api/messages/$M2 -; S2=$(printf '%s' "$BODY" | jq -r .status); NOTE2=$(printf '%s' "$BODY" | jq -r '.approval.decision')
rec $C "M2 (corrected copy) REJECTED, approval.decision REJECT" $([ "$S2" = REJECTED ] && [ "$NOTE2" = REJECT ] && echo PASS || echo FAIL) "status=$S2 decision=$NOTE2"
# A pending message cancelled through the supported action (API; the browser path for cancel is in the browser log).
POL=$(req testuser "$A" GET /api/customers/$CUST/follow-up-policy -; etag)
req testuser "$A" POST /api/customers/$CUST/messages "$(jq -nc --arg c "$CONTACT" '{contactId:$c,action:"REPEAT_PURCHASE_FOLLOW_UP",templateIntent:"REPEAT_PURCHASE",body:"QA334 probe pending cancel",locale:"es-419",origin:"MANUAL"}')" -H "If-Match: $POL" -H "Idempotency-Key: $(uuidgen)"
M3=$(printf '%s' "$BODY" | jq -r .messageId); V3=$(etag); echo "M3=$M3" >> "$OUTD/ids"
req testuser "$A" POST /api/messages/$M3/cancellations '{"note":"QA334 probe"}' -H "If-Match: $V3" -H "Idempotency-Key: $(uuidgen)"
rec $C "pending probe message cancelled via cancellations" $([ "$(printf '%s' "$BODY" | jq -r .status)" = CANCELLED ] && echo PASS || echo FAIL) "http=$CODE msg=${M3:0:8}"
for m in $M1 $M2 $M3; do req testuser "$A" GET /api/messages/$m/events -; echo "  ${m:0:8} $(printf '%s' "$BODY" | jq -c '[.events[].type]')"; done
rec $C "no provider side effect: no send attempt rows, no stub send" $([ "$(psqlq 'select count(*) from dokene.outbound_send_attempts')" = 0 ] && [ "$(stub_sends)" = 0 ] && echo PASS || echo FAIL) "attempts=$(psqlq 'select count(*) from dokene.outbound_send_attempts') stub=$(stub_sends)"
