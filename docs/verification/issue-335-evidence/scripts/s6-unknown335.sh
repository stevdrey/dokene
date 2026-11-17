#!/usr/bin/env bash
# ABOUTME: Suite #335 SEND-06 API part for the hanging-provider case: unknown outcome recorded, resend refused without a
# ABOUTME: provider request, T12 resolution API-only (OPERATOR 403, OWNER resolves to FAILED; never SENT, no provider call).
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa335.sh"
assert_safe_stack true || exit 2
for u in testuser testoperator; do qa_login $u || exit 3; done
C=SEND-06; M=$M_S6HANG
S0=$(stub_sends)
req testuser "$A" GET "/api/messages/$M" -
rec $C "stub hang past the 10 s dispatch deadline: SENDING, outcomeUnknown=true, attempt OUTCOME_UNKNOWN, no provider id" "$([ "$(jb .status)" = SENDING ] && [ "$(jb .outcomeUnknown)" = true ] && [ "$(jb '[.attempts[].outcome]|join(",")')" = OUTCOME_UNKNOWN ] && [ "$(jb .providerMessageId)" = null ] && echo PASS || echo FAIL)" "status=$(jb .status) unknown=$(jb .outcomeUnknown) attempts=$(jb '[.attempts[].outcome]|join(",")') pid=$(jb .providerMessageId) events=$(events "$M")"
stub_mode send accept
send testuser "$M"; H=$CODE; G=$(code)
rec $C "resend with a new key refused while the outcome is unknown: no attempt, no provider request" "$([ "$H" = 409 ] && [ "$(attempts "$M")" = 1 ] && [ "$(stub_sends)" = "$S0" ] && echo PASS || echo FAIL)" "http=$H code=$G attempts=$(attempts "$M") stub_delta=$(( $(stub_sends) - S0 ))"
req testuser "$A" GET "/api/messages/$M" -; V=$(etag)
req testoperator "$A" POST "/api/messages/$M/resolutions" '{"outcome":"FAILED","note":"QA335 checked the stub"}' -H "If-Match: $V" -H "Idempotency-Key: $(uuidgen)"
rec $C "T12 resolution by OPERATOR (no INTEGRATION_MANAGE) refused 403" "$([ "$CODE" = 403 ] && echo PASS || echo FAIL)" "http=$CODE code=$(code)"
req testuser "$A" POST "/api/messages/$M/resolutions" '{"outcome":"SENT"}' -H "If-Match: $V" -H "Idempotency-Key: $(uuidgen)"
rec $C "T12 resolution to SENT is not in the contract: 400 INVALID_INPUT" "$([ "$CODE" = 400 ] && echo PASS || echo FAIL)" "http=$CODE code=$(code)"
req testuser "$A" POST "/api/messages/$M/resolutions" '{"outcome":"FAILED","note":"QA335 checked the stub: no accepted id"}' -H "If-Match: $V" -H "Idempotency-Key: $(uuidgen)"
rec $C "T12 resolution by OWNER: FAILED, outcomeUnknown cleared, no provider request" "$([ "$CODE" = 201 ] || [ "$CODE" = 200 ]; [ "$(jb .status)" = FAILED ] && [ "$(stub_sends)" = "$S0" ] && echo PASS || echo FAIL)" "http=$CODE status=$(jb .status) unknown=$(jb .outcomeUnknown) failure=$(jb .failureCategory) stub_delta=$(( $(stub_sends) - S0 )) events=$(events "$M")"
send testuser "$M"
rec $C "send on the resolved FAILED message refused INVALID_TRANSITION (no blind resend)" "$([ "$(code)" = INVALID_TRANSITION ] && [ "$(stub_sends)" = "$S0" ] && echo PASS || echo FAIL)" "http=$CODE code=$(code)"
qa_finish
