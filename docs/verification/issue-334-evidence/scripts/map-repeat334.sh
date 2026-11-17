#!/usr/bin/env bash
# ABOUTME: Suite #334 setup: map the REPEAT_PURCHASE intent (the action the live AI recommended) to a stub-approved
# ABOUTME: template for tenant A through the API (mapping writes are API-only), then refresh its approval status.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa334.sh"; [ -f "$OUTD/ids" ] && . "$OUTD/ids"
qa_login testuser
I=REPEAT_PURCHASE
req testuser "$A" PUT /api/templates/$I '{"providerTemplateName":"qa334_repeat_purchase","language":"es","parameterNames":["customer_name","message_body"],"enabled":true}' -H 'If-None-Match: *'
rec SETUP "map $I (API)" NOTE "http=$CODE $(printf '%s' "$BODY" | jq -c '{providerTemplateName,approvalStatus,version}')"
V=$(printf '%s' "$BODY" | jq -r .version)
req testuser "$A" POST /api/templates/$I/approval-refresh - -H "If-Match: \"$V\""
rec SETUP "refresh $I against the stub" NOTE "http=$CODE $(printf '%s' "$BODY" | jq -c '{approvalStatus,sendable,version}')"
