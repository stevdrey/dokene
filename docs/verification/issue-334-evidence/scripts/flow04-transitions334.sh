#!/usr/bin/env bash
# ABOUTME: Suite #334 FLOW-04: terminal statuses (CANCELLED, REJECTED) refuse approve, send and cancel with the
# ABOUTME: message's current ETag, and nothing reaches the provider. Global outbound OFF.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa334.sh"; [ -f "$OUTD/ids" ] && . "$OUTD/ids"
C=FLOW-04
qa_login testuser
cur() { req testuser "$A" GET /api/messages/$1 -; V=$(etag); }
code() { printf '%s' "$BODY" | jq -r .code; }
REV=$(printf '0%.0s' {1..64})
cur "$M1"; req testuser "$A" POST /api/messages/$M1/approvals "{\"decision\":\"APPROVE\",\"templateRevision\":\"$REV\"}" -H "If-Match: $V" -H "Idempotency-Key: $(uuidgen)"
rec $C "approve a CANCELLED message (current ETag $V) refused" $([ "$CODE" = 409 ] && echo PASS || echo FAIL) "http=$CODE code=$(code)"
cur "$M1"; req testuser "$A" POST /api/messages/$M1/sends - -H "If-Match: $V" -H "Idempotency-Key: $(uuidgen)"
rec $C "send a CANCELLED message refused" $([ "${CODE:0:1}" = 4 ] && echo PASS || echo FAIL) "http=$CODE code=$(code)"
cur "$M2"; req testuser "$A" POST /api/messages/$M2/cancellations '{}' -H "If-Match: $V" -H "Idempotency-Key: $(uuidgen)"
rec $C "cancel a REJECTED message refused" $([ "$CODE" = 409 ] && echo PASS || echo FAIL) "http=$CODE code=$(code)"
cur "$M2"; req testuser "$A" POST /api/messages/$M2/approvals "{\"decision\":\"APPROVE\",\"templateRevision\":\"$REV\"}" -H "If-Match: $V" -H "Idempotency-Key: $(uuidgen)"
rec $C "approve a REJECTED message refused" $([ "$CODE" = 409 ] && echo PASS || echo FAIL) "http=$CODE code=$(code)"
cur "$M2"; req testuser "$A" POST /api/messages/$M2/sends - -H "If-Match: $V" -H "Idempotency-Key: $(uuidgen)"
rec $C "send a REJECTED message refused" $([ "${CODE:0:1}" = 4 ] && echo PASS || echo FAIL) "http=$CODE code=$(code)"
rec $C "no provider side effect after the transition probes" $([ "$(psqlq 'select count(*) from dokene.outbound_send_attempts')" = 0 ] && [ "$(stub_sends)" = 0 ] && echo PASS || echo FAIL) "attempts=$(psqlq 'select count(*) from dokene.outbound_send_attempts') stub=$(stub_sends)"
qa_finish
