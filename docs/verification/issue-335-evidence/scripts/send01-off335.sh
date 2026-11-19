#!/usr/bin/env bash
# ABOUTME: Suite #335 SEND-01 (global OFF part): authenticated sends of APPROVED synthetic messages are refused safely
# ABOUTME: for OWNER and OPERATOR, with zero attempt rows and zero provider requests. Replays and repeats stay refused.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa335.sh"
assert_safe_stack false || exit 2
C=SEND-01
for u in testuser testoperator; do qa_login $u || exit 3; done
integ; rec $C "baseline: global OFF (backend env), tenant A enabled/proven/outbound" "$([ "$(tenant_state)" = true/true/false ] && echo PASS || echo FAIL)" "tenant=$(tenant_state)"
S0=$(stub_sends); T0=$(attempts_all)
send testuser "$M_S1"; refused OUTBOUND_DISABLED_GLOBALLY "$M_S1" "$S0" "OWNER send, global OFF (http=$CODE)"
send testoperator "$M_S1"; refused OUTBOUND_DISABLED_GLOBALLY "$M_S1" "$S0" "OPERATOR send, global OFF (http=$CODE)"
K=$(uuidgen); send testuser "$M_S1" "$K"; send testuser "$M_S1" "$K"
refused OUTBOUND_DISABLED_GLOBALLY "$M_S1" "$S0" "same Idempotency-Key repeated, global OFF (http=$CODE)"
IR=$(psqlq "select count(*) from dokene.outbound_message_idempotency_keys where idempotency_key='$K'")
rec $C "no idempotency record is kept for a refused send" "$([ "$IR" = 0 ] && echo PASS || echo FAIL)" "idem_rows=$IR"
for _ in 1 2 3; do send testuser "$M_S5KEY"; done
refused OUTBOUND_DISABLED_GLOBALLY "$M_S5KEY" "$S0" "three rapid sends of another APPROVED message (http=$CODE)"
rec $C "global OFF totals: no new attempt rows anywhere, no provider request" "$([ "$(attempts_all)" = "$T0" ] && [ "$(stub_sends)" = "$S0" ] && echo PASS || echo FAIL)" "attempts_total=$(attempts_all) (before $T0) stub_sends=$(stub_sends) (before $S0)"
send testuser "$M_S1"; rec $C "refusal body carries only the error contract (no message, customer or provider data)" NOTE "http=$CODE body=$(printf '%s' "$BODY" | jq -c '{code, keys: keys}' 2>/dev/null)"
qa_finish
