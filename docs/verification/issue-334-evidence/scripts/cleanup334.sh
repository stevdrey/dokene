#!/usr/bin/env bash
# ABOUTME: Suite #334 cleanup: cancel any open probe message, restore the DUE customer's name and due date, return
# ABOUTME: Dario to his NOT_YET_DUE fixture state, and report final counts. Global outbound stays OFF.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa334.sh"; [ -f "$OUTD/ids" ] && . "$OUTD/ids"
assert_safe_stack false || exit 2
qa_login testuser
for m in $(psqlq "select id from dokene.outbound_messages where tenant_id='$A' and status in ('PENDING_APPROVAL','APPROVED')"); do
  req testuser "$A" GET /api/messages/$m -; req testuser "$A" POST /api/messages/$m/cancellations '{"note":"QA334 cleanup"}' -H "If-Match: $(etag)" -H "Idempotency-Key: $(uuidgen)"
  rec CLEANUP "cancel open message ${m:0:8}" NOTE "http=$CODE status=$(printf '%s' "$BODY" | jq -r '.status // .code')"
done
req testuser "$A" GET /api/customers/$CUST -
req testuser "$A" PUT /api/customers/$CUST '{"displayName":"QA333 Ana Vencida","notes":null,"phones":[{"number":"+56983330001","region":"CL","primary":true}]}' -H "If-Match: $(etag)"
rec CLEANUP "restore customer name QA333 Ana Vencida (same contact id)" NOTE "http=$CODE contact=$(printf '%s' "$BODY" | jq -r '.phones[0].id[0:8]')"
req testuser "$A" GET /api/customers/$CUST/follow-up-policy -
req testuser "$A" PUT /api/customers/$CUST/follow-up-policy "{\"cadenceDays\":null,\"explicitNextDate\":\"$(date -u +%F)\",\"snoozedUntil\":null}" -H "If-Match: $(etag)"
rec CLEANUP "Ana due today again (explicitNextDate) for later suites" NOTE "http=$CODE"
D=881495df-5819-4d87-acfe-d02593ff315c
req testuser "$A" GET /api/customers/$D/follow-up-policy -
req testuser "$A" PUT /api/customers/$D/follow-up-policy '{"cadenceDays":null,"explicitNextDate":null,"snoozedUntil":null}' -H "If-Match: $(etag)"
req testuser "$A" GET /api/customers/$D/follow-up-eligibility -
rec CLEANUP "Dario back to NOT_YET_DUE" NOTE "status=$(printf '%s' "$BODY" | jq -r .status)"
rec CLEANUP "final counts" NOTE "messages=$(psqlq "select string_agg(status||':'||n,',') from (select status,count(*) n from dokene.outbound_messages group by 1 order by 1) s") attempts=$(psqlq 'select count(*) from dokene.outbound_send_attempts') stub_sends=$(stub_sends) tenantA=$(tenant_state)"
