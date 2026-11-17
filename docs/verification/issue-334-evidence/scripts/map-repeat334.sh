#!/usr/bin/env bash
# ABOUTME: Suite #334 setup: map the REPEAT_PURCHASE intent (the action the live AI recommended) to a stub-approved
# ABOUTME: template for tenant A through the API (mapping writes are API-only), then refresh its approval status.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa334.sh"; [ -f "$OUTD/ids" ] && . "$OUTD/ids"
qa_login testuser
I=REPEAT_PURCHASE
# Same upsert as env05-templates333.sh: create when unmapped, otherwise replace the current version, so a rerun works.
ver() { req testuser "$A" GET /api/templates -; printf '%s' "$BODY" | jq -r --arg i $I '.items[]|select(.templateIntent==$i).version'; }
V=$(ver); if [ "$V" = null ]; then PRE=(-H 'If-None-Match: *'); else PRE=(-H "If-Match: \"$V\""); fi
req testuser "$A" PUT /api/templates/$I '{"providerTemplateName":"qa334_repeat_purchase","language":"es","parameterNames":["customer_name","message_body"],"enabled":true}' "${PRE[@]}"
rec SETUP "map $I (API)" "$([ "$CODE" = 200 ] && echo PASS || echo FAIL)" "http=$CODE $(printf '%s' "$BODY" | jq -c '{providerTemplateName,approvalStatus,version}')"
req testuser "$A" POST /api/templates/$I/approval-refresh - -H "If-Match: \"$(ver)\""
rec SETUP "refresh $I against the stub -> APPROVED" "$([ "$CODE" = 200 ] && [ "$(printf '%s' "$BODY" | jq -r .approvalStatus)" = APPROVED ] && echo PASS || echo FAIL)" "http=$CODE $(printf '%s' "$BODY" | jq -c '{approvalStatus,sendable,version}')"
qa_finish
