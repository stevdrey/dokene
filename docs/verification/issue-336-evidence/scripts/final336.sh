#!/usr/bin/env bash
# ABOUTME: Suite #336 end state: switches OFF, no provider request since the W1 sends, the inbox summary, then cancels
# ABOUTME: this suite's open messages and archives the disposable QA336 customers.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa336.sh"
assert_safe_stack false || exit 2
qa_login testuser || exit 3
rec END "Graph base URL loopback stub, global switch OFF (backend env), tenant A enabled/proven/outbound OFF" "$([ "$(tenant_state)" = true/true/false ] && echo PASS || echo FAIL)" "tenant=$(tenant_state)"
rec END "provider send requests since the W1 sends: none (every callback, refusal and T12 made zero provider requests)" "$([ "$(stub_sends)" = "${W1_STUB_END:-22}" ] && echo PASS || echo FAIL)" "stub_sends=$(stub_sends) expected=${W1_STUB_END:-22}"
rec END "inbox summary" NOTE "$(psqlq "select string_agg(k||':'||n, ',') from (select case when tenant_id='$A' then 'A' else 'B' end||'/'||delivery_status||'/'||state k, count(*) n from dokene.provider_webhook_events group by 1 order by 1) s")"
for v in $(grep -o '^M_[A-Z0-9_]*' "$OUTD/ids" | sort -u); do
  m=${!v}; req testuser "$A" GET "/api/messages/$m" -
  case "$(jb .status)" in PENDING_APPROVAL|APPROVED) ;; *) continue ;; esac
  req testuser "$A" POST "/api/messages/$m/cancellations" '{"note":"QA336 cleanup"}' -H "If-Match: $(etag)" -H "Idempotency-Key: $(uuidgen)"
  rec CLEANUP "cancel open suite message $v ${m:0:8}" "$([ "$(jb .status)" = CANCELLED ] && echo PASS || echo FAIL)" "http=$CODE"
done
for v in $(grep -o '^H[A-Z0-9]*=' "$OUTD/ids" | tr -d = | sort -u); do
  c=${!v}; req testuser "$A" GET "/api/customers/$c" -
  [ "$(jb .status)" = ACTIVE ] || continue
  req testuser "$A" DELETE "/api/customers/$c" - -H "If-Match: $(etag)"
  rec CLEANUP "archive disposable customer $v" "$([ "$CODE" = 204 ] && echo PASS || echo FAIL)" "http=$CODE"
done
stub_reset
rec CLEANUP "final counts" NOTE "messages=$(psqlq "select string_agg(status||':'||n,',') from (select status,count(*) n from dokene.outbound_messages group by 1 order by 1) s") attempts=$(psqlq "select string_agg(outcome||':'||n,',') from (select outcome,count(*) n from dokene.outbound_send_attempts group by 1 order by 1) s") stub_sends=$(stub_sends) tenantA=$(tenant_state)"
qa_finish
