#!/usr/bin/env bash
# ABOUTME: Suite #337 end state: switches off, no provider send during the suite, tenant B integration restored; cancels
# ABOUTME: the suite's open messages in both tenants and archives the disposable QA337 customers.
set -uo pipefail
. "$(dirname "$0")/qa337.sh"
assert_safe_stack false || exit 2
qa_login testuser || exit 3
req testuser "$A" GET /api/integrations/whatsapp -; ta="$(jb .enabled)/$(jb .webhookVerified)/$(jb .outboundEnabled)"
req testuser "$B" GET /api/integrations/whatsapp -; tb="$(jb .enabled)/$(jb .webhookVerified)/$(jb .outboundEnabled)/$(jb .secretRef)/$(jb .phoneNumberId)"
rec END "global switch off (backend env), tenant A enabled/proven/outbound off, tenant B restored to its own number and reference" "$( { [ "$ta" = true/true/false ] && [ "$tb" = true/true/false/qa333-token-b/100000000000002 ]; } && echo PASS || echo FAIL)" "A=$ta B=$tb"
rec END "no provider send during the whole suite (stub send count unchanged since fixtures337.sh)" "$( { [ "$(stub_sends)" = "${STUB0:?run fixtures337.sh first}" ]; } && echo PASS || echo FAIL)" "stub_sends=$(stub_sends) start=$STUB0"
for v in MA PC PT XM NX; do m=${!v}; req testuser "$A" GET "/api/messages/$m" -
  case "$(jb .status)" in PENDING_APPROVAL|APPROVED) ;; *) continue ;; esac
  req testuser "$A" POST "/api/messages/$m/cancellations" '{"note":"QA337 cleanup"}' -H "If-Match: $(etag)" -H "Idempotency-Key: $(uuidgen)"
  rec CLEANUP "cancel open tenant A message $v" "$( { [ "$(jb .status)" = CANCELLED ]; } && echo PASS || echo FAIL)" "http=$CODE"
done
req testuser "$B" GET "/api/messages/$MB" -
req testuser "$B" POST "/api/messages/$MB/cancellations" '{"note":"QA337 cleanup"}' -H "If-Match: $(etag)" -H "Idempotency-Key: $(uuidgen)"
rec CLEANUP "cancel tenant B message MB" "$( { [ "$(jb .status)" = CANCELLED ]; } && echo PASS || echo FAIL)" "http=$CODE"
for v in RA IA XA; do c=${!v}; req testuser "$A" GET "/api/customers/$c" -; [ "$(jb .status)" = ACTIVE ] || continue
  req testuser "$A" DELETE "/api/customers/$c" - -H "If-Match: $(etag)"; rec CLEANUP "archive tenant A customer $v" "$( { [ "$CODE" = 204 ]; } && echo PASS || echo FAIL)" "http=$CODE"; done
req testuser "$B" GET "/api/customers/$IB" -; req testuser "$B" DELETE "/api/customers/$IB" - -H "If-Match: $(etag)"
rec CLEANUP "archive tenant B customer IB" "$( { [ "$CODE" = 204 ]; } && echo PASS || echo FAIL)" "http=$CODE"
rec CLEANUP "final counts" NOTE "messages=$(psqlq "select string_agg(status||':'||n,',') from (select status,count(*) n from dokene.outbound_messages group by 1 order by 1) s") attempts=$(psqlq 'select count(*) from dokene.outbound_send_attempts') denials=$(deny_n) stub_sends=$(stub_sends)"
# Recorded FAIL lines make the exit status nonzero.
qa_finish
