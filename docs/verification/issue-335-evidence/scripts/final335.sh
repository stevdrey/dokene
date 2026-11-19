#!/usr/bin/env bash
# ABOUTME: Suite #335 end state (SEND-07): global and tenant switches OFF, baseline send policy (24h interval, default
# ABOUTME: recovery) back in the backend, approved messages refused again; then cancels this suite's open messages.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa335.sh"
assert_safe_stack false || exit 2
qa_login testuser || exit 3
C=SEND-07
rec $C "end: Graph base URL loopback stub, global switch OFF (backend env), tenant A outbound OFF" "$([ "$(tenant_state)" = true/true/false ] && echo PASS || echo FAIL)" "tenant=$(tenant_state)"
if assert_baseline_policy; then B=PASS; else B=FAIL; fi
rec $C "end: no send policy override in the backend env, so min-contact-interval is the application default 24h (application.yml) and recovery uses its defaults" $B "$(grep -n 'min-contact-interval' "$WT/backend/src/main/resources/application.yml" | sed 's/^ *//')"
S0=$(stub_sends); send testuser "$M_S1"
refused OUTBOUND_DISABLED_GLOBALLY "$M_S1" "$S0" "end: APPROVED message refused again with the switches OFF (http=$CODE)"
# Cleanup: cancel every still-open message of this suite (ids in $OUTD/ids).
for v in $(grep -o '^M_[A-Z0-9]*' "$OUTD/ids" | sort -u); do
  m=${!v}; req testuser "$A" GET "/api/messages/$m" -
  case "$(jb .status)" in PENDING_APPROVAL|APPROVED) ;; *) continue ;; esac
  req testuser "$A" POST "/api/messages/$m/cancellations" '{"note":"QA335 cleanup"}' -H "If-Match: $(etag)" -H "Idempotency-Key: $(uuidgen)"
  rec CLEANUP "cancel open suite message $v ${m:0:8}" "$([ "$(jb .status)" = CANCELLED ] && echo PASS || echo FAIL)" "http=$CODE"
done
# Archive the disposable QA335 customers so later suites see only their own fixtures in the queue.
for v in $(grep -o '^S[A-Z0-9]*=' "$OUTD/ids" | tr -d = | sort -u); do
  c=${!v}; req testuser "$A" GET "/api/customers/$c" -
  [ "$(jb .status)" = ACTIVE ] || continue
  req testuser "$A" DELETE "/api/customers/$c" - -H "If-Match: $(etag)"
  rec CLEANUP "archive disposable customer $v" "$([ "$CODE" = 204 ] && echo PASS || echo FAIL)" "http=$CODE"
done
stub_reset
rec CLEANUP "final counts" NOTE "messages=$(psqlq "select string_agg(status||':'||n,',') from (select status,count(*) n from dokene.outbound_messages group by 1 order by 1) s") attempts=$(psqlq "select string_agg(outcome||':'||n,',') from (select outcome,count(*) n from dokene.outbound_send_attempts group by 1 order by 1) s") stub_sends=$(stub_sends) tenantA=$(tenant_state)"
qa_finish
