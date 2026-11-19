#!/usr/bin/env bash
# ABOUTME: Suite #340 REG-02 read-back after the live recommendation/draft/edit/copy in the browser: consent, follow-up
# ABOUTME: policy, messages, approvals and provider counters are unchanged; the AI calls are counted from the audit trail.
set -uo pipefail
. "$(dirname "$0")/qa340.sh"
C=REG-02
qa_login testuser || exit 1
req testuser "$A" GET "/api/customers/$AI_C/contact-policy" -
chk $C "consent/DNC and contact-policy version unchanged by generation" "$AI_POL0" "$(printf '%s' "$(etag)|$(jb '[.doNotContact, (.consents|map(.status)|join(","))]|@json')" | md5sum | cut -c1-12)"
req testuser "$A" GET "/api/customers/$AI_C/follow-up-policy" -
chk $C "follow-up cadence/explicit date/snooze and version unchanged" "$AI_FU0" "$(printf '%s' "$(etag)|$(jb '[.cadenceDays,.explicitNextDate,.snoozedUntil]|@json')" | md5sum | cut -c1-12)"
req testuser "$A" GET "/api/customers/$AI_C/follow-up-eligibility" -; chk $C "customer still DUE" DUE "$(jb .status)"
chk $C "no message, approval or send attempt created for the customer" "0 0" \
  "$(psqlq "select count(*) from dokene.outbound_messages where customer_id='$AI_C'") $(psqlq "select count(*) from dokene.outbound_send_attempts a join dokene.outbound_messages m on m.id=a.message_id where m.customer_id='$AI_C'")"
chk $C "no provider request (stub sends unchanged)" "$STUB0_340" "$(stub_sends)"
rec $C "live AI invocations since the provider switch (audit AI_INVOCATION_OUTCOME)" NOTE \
  "new_rows=$(( $(ai_n) - AI0_340 )) outcomes=$(psqlq "select string_agg(ai_operation||':'||ai_outcome,',' order by occurred_at) from (select * from dokene.audit_events where event_type='AI_INVOCATION_OUTCOME' order by occurred_at desc limit $(( $(ai_n) - AI0_340 ))) x")"
qa_finish
