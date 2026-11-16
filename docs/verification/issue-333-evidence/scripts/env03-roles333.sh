#!/usr/bin/env bash
# ABOUTME: P3-MQA-ENV-03 role probes on the integration and template routes: OWNER/ADMIN read and manage, OPERATOR and
# ABOUTME: VIEWER read only for the integration; every refused write changes nothing. Usage: env03-roles333.sh <label>
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa333.sh"
. "$OUTD/ids"
L=${1:-run}; C=ENV-03
for u in testuser testadmin testoperator testviewer testnone; do qa_login $u; done
req testuser "$A" GET /api/integrations/whatsapp -; BASE_CODE=$CODE; BASE_ETAG=$(etag); BASE=$(printf '%s' "$BODY" | jq -cS 'del(.version)' 2>/dev/null)
rec $C "[$L] OWNER reads integration" NOTE "http=$CODE etag=$BASE_ETAG body=$(printf '%s' "$BODY" | jq -c '{enabled,outboundEnabled,webhookVerified,secretRef,code}' 2>/dev/null) cache=$(hdr cache-control)"
for u in testadmin testoperator testviewer; do
  req $u "$A" GET /api/integrations/whatsapp -
  rec $C "[$L] $u reads integration like OWNER" $([ "$CODE" = "$BASE_CODE" ] && echo PASS || echo FAIL) "http=$CODE"
done
req testnone "$A" GET /api/integrations/whatsapp -; rec $C "[$L] non-member read refused" $([ "$CODE" = 403 ] && echo PASS || echo FAIL) "http=$CODE"
# write attempts by OPERATOR and VIEWER on every manage route, with a valid precondition so only the role decides
if [ "$BASE_CODE" = 200 ]; then PRE=(-H "If-Match: $BASE_ETAG"); else PRE=(-H 'If-None-Match: *'); fi
CFG='{"phoneNumberId":"100000000000001","businessAccountId":"200000000000001","displayPhoneNumber":"+50600000001","enabled":true,"secretRef":"qa333-token-a"}'
for u in testoperator testviewer; do
  req $u "$A" PUT /api/integrations/whatsapp "$CFG" "${PRE[@]}";                     rec $C "[$L] $u PUT integration refused" $([ "$CODE" = 403 ] && echo PASS || echo FAIL) "http=$CODE code=$(printf '%s' "$BODY" | jq -r '.code // empty' 2>/dev/null)"
  req $u "$A" PUT /api/integrations/whatsapp/outbound '{"enabled":true}' -H "If-Match: ${BASE_ETAG:-\"0\"}"; rec $C "[$L] $u tenant outbound switch refused" $([ "$CODE" = 403 ] && echo PASS || echo FAIL) "http=$CODE"
  req $u "$A" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: ${BASE_ETAG:-\"0\"}"; rec $C "[$L] $u ownership proof refused" $([ "$CODE" = 403 ] && echo PASS || echo FAIL) "http=$CODE"
  req $u "$A" POST /api/integrations/whatsapp/credential-rotations - -H "If-Match: ${BASE_ETAG:-\"0\"}"; rec $C "[$L] $u credential rotation refused" $([ "$CODE" = 403 ] && echo PASS || echo FAIL) "http=$CODE"
done
req testviewer "$A" PUT /api/templates/GENERAL_FOLLOW_UP '{"providerTemplateName":"qa333_probe","language":"es","parameterNames":["customer_name"],"enabled":true}' -H 'If-None-Match: *'
rec $C "[$L] VIEWER template write refused" $([ "$CODE" = 403 ] && echo PASS || echo FAIL) "http=$CODE"
req testuser "$A" GET /api/integrations/whatsapp -
rec $C "[$L] refused writes changed nothing" $([ "$CODE" = "$BASE_CODE" ] && [ "$(etag)" = "$BASE_ETAG" ] && [ "$(printf '%s' "$BODY" | jq -cS 'del(.version)' 2>/dev/null)" = "$BASE" ] && echo PASS || echo FAIL) "http=$CODE etag=$(etag)"
for u in testuser testoperator testviewer; do req $u "$A" GET /api/templates -; rec $C "[$L] $u reads templates" $([ "$CODE" = 200 ] && echo PASS || echo FAIL) "http=$CODE n=$(printf '%s' "$BODY" | jq '.items|length' 2>/dev/null) sendable=$(printf '%s' "$BODY" | jq -c '[.items[]?.sendable]|unique' 2>/dev/null)"; done
# global switch has no route
req testuser "$A" PUT /api/integrations/whatsapp/global-outbound '{"enabled":true}' -H "If-Match: ${BASE_ETAG:-\"0\"}"
rec $C "[$L] no API route for the global kill switch" $([ "$CODE" = 404 ] || [ "$CODE" = 405 ] && echo PASS || echo FAIL) "http=$CODE"
