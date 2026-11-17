#!/usr/bin/env bash
# ABOUTME: Suite #335 window W1, SEND-03 integration and mapping part (runs last in W1): mapping disabled, mapping
# ABOUTME: changed, integration disabled, credential rotated (unproven, then re-proven). No provider send for any of them.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa335.sh"
assert_safe_stack true || exit 2
stub_reset
qa_login testuser || exit 3
C=SEND-03
I=GENERAL_FOLLOW_UP
tver() { req testuser "$A" GET /api/templates -; jb ".items[]|select(.templateIntent==\"$I\").version"; }
tmap() { # providerTemplateName enabled
  local v; v=$(tver)
  req testuser "$A" PUT /api/templates/$I "$(jq -nc --arg n "$1" --argjson e "$2" '{providerTemplateName:$n,language:"es",parameterNames:["customer_name","message_body"],enabled:$e}')" -H "If-Match: \"$v\""
  local h=$CODE; v=$(tver)
  [ "$2" = true ] && req testuser "$A" POST /api/templates/$I/approval-refresh - -H "If-Match: \"$v\""
  printf 'put=%s refresh=%s sendable=%s' "$h" "$CODE" "$(jb .sendable)"
}
S0=$(stub_sends)
before() { S0=$(stub_sends); }
# 1. Mapping disabled after approval.
R=$(tmap qa333_general_follow_up false)
before; send testuser "$M_S3MAPD"; H=$CODE; G=$(code)
rec $C "GENERAL_FOLLOW_UP mapping disabled after approval ($R): send refused, 0 attempts, 0 provider sends" "$([ "$H" = 409 ] && [ "$(attempts "$M_S3MAPD")" = 0 ] && [ "$(stub_sends)" = "$S0" ] && echo PASS || echo FAIL)" "http=$H code=$G state=$(mstate "$M_S3MAPD")"
R=$(tmap qa333_general_follow_up true); rec $C "mapping re-enabled unchanged" NOTE "$R"
# 2. Mapping changed to another provider template after approval: the bound fingerprint no longer covers it.
R=$(tmap qa335_general_changed true)
before; send testuser "$M_S3MAPM"; refused APPROVAL_INVALIDATED "$M_S3MAPM" "$S0" "mapping changed to another template after approval ($R; http=$CODE)"
R=$(tmap qa333_general_follow_up true); rec $C "mapping restored to qa333_general_follow_up" NOTE "$R"
# 3. Integration disabled after approval.
integ; CFG=$(printf '%s' "$BODY" | jq -c '{phoneNumberId,businessAccountId,displayPhoneNumber,enabled:false,secretRef}')
req testuser "$A" PUT /api/integrations/whatsapp "$CFG" -H "If-Match: $(etag)"; P=$CODE
before; send testuser "$M_S3INT"; refused INTEGRATION_DISABLED "$M_S3INT" "$S0" "integration disabled after approval (put http=$P, send http=$CODE)"
integ; CFG=$(printf '%s' "$BODY" | jq -c '{phoneNumberId,businessAccountId,displayPhoneNumber,enabled:true,secretRef}')
req testuser "$A" PUT /api/integrations/whatsapp "$CFG" -H "If-Match: $(etag)"
rec $C "integration re-enabled" NOTE "http=$CODE enabled/proven/outbound=$(tenant_state)"
if [ "$(tenant_state)" != true/true/true ]; then
  integ; req testuser "$A" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: $(etag)"
  integ; req testuser "$A" PUT /api/integrations/whatsapp/outbound '{"enabled":true}' -H "If-Match: $(etag)"
  rec $C "re-proved and tenant switch on after re-enable" NOTE "state=$(tenant_state)"
fi
# 4. Credential rotated after approval: the integration is unproven until ownership is proven again; after the re-proof
#    the approval no longer covers the sender revision.
integ; req testuser "$A" POST /api/integrations/whatsapp/credential-rotations - -H "If-Match: $(etag)"
rec $C "credential rotated" NOTE "http=$CODE enabled/proven/outbound=$(tenant_state)"
before; send testuser "$M_S3PROOF"; H=$CODE; G=$(code)
rec $C "send while the rotated integration is unproven: refused, 0 attempts, 0 provider sends" "$([ "$H" = 409 ] && [ "$(attempts "$M_S3PROOF")" = 0 ] && [ "$(stub_sends)" = "$S0" ] && echo PASS || echo FAIL)" "http=$H code=$G state=$(mstate "$M_S3PROOF")"
integ; req testuser "$A" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: $(etag)"
rec $C "ownership re-proven against the stub" NOTE "http=$CODE enabled/proven/outbound=$(tenant_state)"
if [ "$(tenant_state)" != true/true/true ]; then
  integ; req testuser "$A" PUT /api/integrations/whatsapp/outbound '{"enabled":true}' -H "If-Match: $(etag)"
fi
before; send testuser "$M_S3PROOF"; refused APPROVAL_INVALIDATED "$M_S3PROOF" "$S0" "re-proven integration, approval bound to the old sender revision (http=$CODE)"
before; send testuser "$M_S3INT"; refused APPROVAL_INVALIDATED "$M_S3INT" "$S0" "integration message after rotation (http=$CODE)"
qa_finish
