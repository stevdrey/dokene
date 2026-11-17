#!/usr/bin/env bash
# ABOUTME: Suite #335 window W1, SEND-02 "follow-up made ineligible after approval" (corrected harness): one customer
# ABOUTME: snoozed through the follow-up snooze route, one moved to a future explicit date. Both sends must be refused.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa335.sh"
assert_safe_stack true || exit 2
stub_reset
qa_login testuser || exit 3
C=SEND-02
mkcust S2SNZ "QA335 Lidia Pospuesta Dos" && mkmsg M_S2SNZ testuser "$S2SNZ" "$S2SNZ_C" approve || exit 4
mkcust S2FUT "QA335 Mateo Fecha Futura" && mkmsg M_S2FUT testuser "$S2FUT" "$S2FUT_C" approve || exit 4
S0=$(stub_sends)
req testuser "$A" GET "/api/customers/$S2SNZ/follow-up-policy" -
req testuser "$A" PUT "/api/customers/$S2SNZ/follow-up-snooze" "{\"until\":\"$(date -u -d '+7 days' +%F)\"}" -H "If-Match: $(etag)"
P="$CODE snoozedUntil=$(jb .snoozedUntil)"; req testuser "$A" GET "/api/customers/$S2SNZ/follow-up-eligibility" -; E=$(jb .status)
send testuser "$M_S2SNZ"; refused FOLLOW_UP_INELIGIBLE "$M_S2SNZ" "$S0" "follow-up snoozed 7 days after approval via the snooze route (snooze http=$P eligibility=$E, send http=$CODE)"
req testuser "$A" GET "/api/customers/$S2FUT/follow-up-policy" -
req testuser "$A" PUT "/api/customers/$S2FUT/follow-up-policy" "{\"cadenceDays\":null,\"explicitNextDate\":\"$(date -u -d '+7 days' +%F)\"}" -H "If-Match: $(etag)"
P=$CODE; req testuser "$A" GET "/api/customers/$S2FUT/follow-up-eligibility" -; E=$(jb .status)
send testuser "$M_S2FUT"; refused FOLLOW_UP_INELIGIBLE "$M_S2FUT" "$S0" "next follow-up date moved 7 days out after approval (policy http=$P eligibility=$E, send http=$CODE)"
qa_finish
