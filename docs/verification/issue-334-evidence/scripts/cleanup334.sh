#!/usr/bin/env bash
# ABOUTME: Suite #334 cleanup: cancel this suite's open messages, restore the DUE customer's name and due date, return
# ABOUTME: Dario to his NOT_YET_DUE fixture state, and report final counts. Global outbound stays OFF.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa334.sh"; [ -f "$OUTD/ids" ] && . "$OUTD/ids"
assert_safe_stack false || exit 2
qa_login testuser
ok() { [ "$CODE" = 200 ] && echo PASS || echo FAIL; }
# Only this suite's messages (ids recorded in $OUTD/ids); other suites' open messages are left alone.
for v in M1 M2 M3 M4 M5 M6 M7; do
  m=${!v:-}; [ -n "$m" ] || continue
  req testuser "$A" GET /api/messages/$m -
  case "$(printf '%s' "$BODY" | jq -r .status)" in PENDING_APPROVAL|APPROVED) ;; *) continue ;; esac
  req testuser "$A" POST /api/messages/$m/cancellations '{"note":"QA334 cleanup"}' -H "If-Match: $(etag)" -H "Idempotency-Key: $(uuidgen)"
  rec CLEANUP "cancel open suite message $v ${m:0:8}" "$([ "$(printf '%s' "$BODY" | jq -r .status)" = CANCELLED ] && echo PASS || echo FAIL)" "http=$CODE"
done
# Rename back with the customer's current phones (existing E.164 numbers keep their contact ids; no region needed).
req testuser "$A" GET /api/customers/$CUST -
PHONES=$(printf '%s' "$BODY" | jq -c '[.phones[]|{number:.e164,region:null,primary}]')
req testuser "$A" PUT /api/customers/$CUST "$(jq -nc --argjson p "$PHONES" '{displayName:"QA333 Ana Vencida",notes:null,phones:$p}')" -H "If-Match: $(etag)"
rec CLEANUP "restore customer name QA333 Ana Vencida (phones and contact ids unchanged)" "$(ok)" "http=$CODE phones=$(printf '%s' "$PHONES" | jq length)"
req testuser "$A" GET /api/customers/$CUST/follow-up-policy -
req testuser "$A" PUT /api/customers/$CUST/follow-up-policy "{\"cadenceDays\":null,\"explicitNextDate\":\"$(date -u +%F)\",\"snoozedUntil\":null}" -H "If-Match: $(etag)"
rec CLEANUP "Ana due today again (explicitNextDate) for later suites" "$(ok)" "http=$CODE"
req testuser "$A" GET /api/customers -
D=$(printf '%s' "$BODY" | jq -r '[.customers[]|select(.displayName=="QA333 Dario Reciente").id][0] // empty')
if [ -z "$D" ]; then rec CLEANUP "Dario back to NOT_YET_DUE" FAIL "customer QA333 Dario Reciente not found"; else
  req testuser "$A" GET /api/customers/$D/follow-up-policy -
  req testuser "$A" PUT /api/customers/$D/follow-up-policy '{"cadenceDays":null,"explicitNextDate":null,"snoozedUntil":null}' -H "If-Match: $(etag)"
  P=$CODE; req testuser "$A" GET /api/customers/$D/follow-up-eligibility -
  rec CLEANUP "Dario back to NOT_YET_DUE" "$([ "$P" = 200 ] && [ "$(printf '%s' "$BODY" | jq -r .status)" = NOT_YET_DUE ] && echo PASS || echo FAIL)" "http=$P status=$(printf '%s' "$BODY" | jq -r .status)"
fi
rec CLEANUP "final counts" NOTE "messages=$(psqlq "select string_agg(status||':'||n,',') from (select status,count(*) n from dokene.outbound_messages group by 1 order by 1) s") attempts=$(psqlq 'select count(*) from dokene.outbound_send_attempts') stub_sends=$(stub_sends) tenantA=$(tenant_state)"
qa_finish
