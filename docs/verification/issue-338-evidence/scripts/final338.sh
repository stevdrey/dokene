#!/usr/bin/env bash
# ABOUTME: Suite #338 end state: switches off, tenant B integration restored, provider sends only inside window W1;
# ABOUTME: cancels the suite's open messages and archives every disposable "QA338" customer in tenant A.
set -uo pipefail
. "$(dirname "$0")/qa338.sh"
assert_safe_stack false || exit 2
qa_login testuser || exit 3
req testuser "$A" GET /api/integrations/whatsapp -; ta="$(jb .enabled)/$(jb .webhookVerified)/$(jb .outboundEnabled)"
req testuser "$B" GET /api/integrations/whatsapp -; tb="$(jb .enabled)/$(jb .webhookVerified)/$(jb .outboundEnabled)/$(jb .secretRef)/$(jb .phoneNumberId)"
rec END "global switch off (backend env), tenant A enabled/proven/outbound off, tenant B restored to its own number and reference" "$( { [ "$ta" = true/true/false ] && [ "$tb" = true/true/false/qa333-token-b/100000000000002 ]; } && echo PASS || echo FAIL)" "A=$ta B=$tb"
# W1 made exactly seven sends (SA, SI, SX from the browser; SD, SR, SF, SU over HTTP).
rec END "provider sends only inside W1: seven, one per W1 message" "$( { [ $(( $(stub_sends) - ${STUB0:?run fixtures337.sh with OUTD first} )) = 7 ] && [ "$(psqlq "select count(*) from dokene.outbound_send_attempts a join dokene.customers c on c.id=(select customer_id from dokene.outbound_messages m where m.id=a.message_id) where c.display_name like 'QA338%'")" = 7 ]; } && echo PASS || echo FAIL)" "stub_sends=$(stub_sends) start=$STUB0"
n=0; f=0
for m in $(psqlq "select m.id from dokene.outbound_messages m join dokene.customers c on c.id=m.customer_id where c.display_name like 'QA338%' and m.status in ('PENDING_APPROVAL','APPROVED')"); do
  req testuser "$A" GET "/api/messages/$m" -
  req testuser "$A" POST "/api/messages/$m/cancellations" '{"note":"QA338 cleanup"}' -H "If-Match: $(etag)" -H "Idempotency-Key: $(uuidgen)"
  n=$((n + 1)); [ "$(jb .status)" = CANCELLED ] || f=$((f + 1))
done
rec CLEANUP "cancel the suite's open messages" "$( { [ "$f" = 0 ]; } && echo PASS || echo FAIL)" "cancelled=$n failed=$f"
n=0; f=0
for c in $(psqlq "select id from dokene.customers where display_name like 'QA338%' and archived_at is null"); do
  req testuser "$A" GET "/api/customers/$c" -; req testuser "$A" DELETE "/api/customers/$c" - -H "If-Match: $(etag)"
  n=$((n + 1)); [ "$CODE" = 204 ] || f=$((f + 1))
done
rec CLEANUP "archive the QA338 customers" "$( { [ "$f" = 0 ] && [ "$(psqlq "select count(*) from dokene.customers where display_name like 'QA338%' and archived_at is null")" = 0 ]; } && echo PASS || echo FAIL)" "archived=$n failed=$f"
rec CLEANUP "final counts" NOTE "messages=$(psqlq "select string_agg(status||':'||n,',') from (select status,count(*) n from dokene.outbound_messages group by 1 order by 1) s") attempts=$(psqlq 'select count(*) from dokene.outbound_send_attempts') stub_sends=$(stub_sends)"
qa_finish
