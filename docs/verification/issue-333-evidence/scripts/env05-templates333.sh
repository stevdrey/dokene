#!/usr/bin/env bash
# ABOUTME: P3-MQA-ENV-05 template mapping lifecycle against the controlled Graph stub: configure, verify, mismatched
# ABOUTME: provider evidence (name, language, schema, status), disable and reverify. Global/tenant outbound stay OFF.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa333.sh"
. "$OUTD/ids"
STUBDIR=$QA_HOME/stub; C=ENV-05; I=GENERAL_FOLLOW_UP
qa_login testuser
stub() { printf '%s' "$2" > "$STUBDIR/$1"; }
cur() { req testuser "$A" GET /api/templates -; printf '%s' "$BODY" | jq -c --arg i $I '.items[]|select(.templateIntent==$i)|{mapped,enabled,providerTemplateName,language,parameterNames,approvalStatus,sendable,version,checked:(.approvalCheckedAt!=null)}'; }
ver() { req testuser "$A" GET /api/templates -; printf '%s' "$BODY" | jq -r --arg i $I '.items[]|select(.templateIntent==$i).version'; }
put() { # enabled name lang params-json
  local v; v=$(ver); local -a pre; if [ "$v" = null ]; then pre=(-H 'If-None-Match: *'); else pre=(-H "If-Match: \"$v\""); fi
  req testuser "$A" PUT /api/templates/$I "$(jq -nc --arg n "$2" --arg l "$3" --argjson p "$4" --argjson e "$1" '{providerTemplateName:$n,language:$l,parameterNames:$p,enabled:$e}')" "${pre[@]}"
}
refresh() { req testuser "$A" POST /api/templates/$I/approval-refresh - -H "If-Match: \"$(ver)\""; }
show() { printf '%s' "$BODY" | jq -c '{approvalStatus,sendable,enabled,version,code}' 2>/dev/null; }
P2='["customer_name","message_body"]'
stub params 'customer_name,message_body'; stub templates approved

put true qa333_general_follow_up es "$P2"; rec $C "OWNER maps $I (es, 2 params)" $([ "$CODE" = 201 ] || [ "$CODE" = 200 ] && echo PASS || echo FAIL) "http=$CODE $(show)"
st=$(printf '%s' "$BODY" | jq -r '"\(.approvalStatus)/\(.sendable)"')
rec $C "new mapping is not sendable before provider evidence" $([ "$st" = UNVERIFIED/false ] && echo PASS || echo FAIL) "$st"
refresh; rec $C "approval refresh with provider APPROVED (exact name/lang/schema)" $([ "$(printf '%s' "$BODY" | jq -r .approvalStatus)" = APPROVED ] && echo PASS || echo FAIL) "http=$CODE $(show)"
rec $C "sendable reported for approved mapping (integration proven, switches off)" NOTE "sendable=$(printf '%s' "$BODY" | jq -r .sendable)"
for mode in otherName otherLanguage absent rejected paused deleted; do
  stub templates $mode; refresh
  rec $C "provider evidence '$mode' -> not approved and not sendable" $([ "$(printf '%s' "$BODY" | jq -r .approvalStatus)" != APPROVED ] && [ "$(printf '%s' "$BODY" | jq -r .sendable)" = false ] && echo PASS || echo FAIL) "http=$CODE $(show)"
done
stub templates approved; stub params 'customer_name'; refresh
rec $C "provider schema mismatch (1 placeholder vs 2 mapped) -> not sendable" $([ "$(printf '%s' "$BODY" | jq -r .sendable)" = false ] && echo PASS || echo FAIL) "http=$CODE $(show)"
stub params 'customer_name,message_body'; stub templates error500; refresh
R5=$CODE; R5C=$(printf '%s' "$BODY" | jq -r '.code // empty' 2>/dev/null); AFTER=$(cur | jq -r .approvalStatus)
rec $C "provider 5xx -> safe problem, no approval invented" $([ "$R5" = 503 ] && [ "$R5C" = PROVIDER_UNAVAILABLE ] && [ "$AFTER" != APPROVED ] && echo PASS || echo FAIL) "http=$R5 code=$R5C stored=$AFTER"
stub templates approved; refresh; rec $C "re-verify after recovery -> APPROVED" $([ "$(printf '%s' "$BODY" | jq -r .approvalStatus)" = APPROVED ] && echo PASS || echo FAIL) "http=$CODE $(show)"
SENDABLE_APPROVED=$(printf '%s' "$BODY" | jq -r .sendable)
put false qa333_general_follow_up es "$P2"; rec $C "OWNER disables mapping -> not sendable" $([ "$(printf '%s' "$BODY" | jq -r .sendable)" = false ] && echo PASS || echo FAIL) "http=$CODE $(show)"
put true qa333_general_follow_up es "$P2"; rec $C "re-enable mapping" NOTE "http=$CODE $(show)"
refresh; rec $C "re-verify after re-enable -> APPROVED, sendable as before" $([ "$(printf '%s' "$BODY" | jq -r .approvalStatus)" = APPROVED ] && [ "$(printf '%s' "$BODY" | jq -r .sendable)" = "$SENDABLE_APPROVED" ] && echo PASS || echo FAIL) "http=$CODE $(show)"
put true qa333_general_follow_up_v2 es "$P2"; rec $C "renaming the provider template drops approval evidence" $([ "$(printf '%s' "$BODY" | jq -r .approvalStatus)" != APPROVED ] && [ "$(printf '%s' "$BODY" | jq -r .sendable)" = false ] && echo PASS || echo FAIL) "http=$CODE $(show)"
put true qa333_general_follow_up es "$P2"; refresh; rec $C "restore approved mapping for later suites" NOTE "http=$CODE $(show)"
req testuser "$A" PUT /api/templates/$I "$(jq -nc '{providerTemplateName:"x",language:"es",parameterNames:["customer_name"],enabled:true}')" -H 'If-Match: "0"'
rec $C "stale If-Match refused" $([ "$CODE" = 412 ] || [ "$CODE" = 409 ] && echo PASS || echo FAIL) "http=$CODE code=$(printf '%s' "$BODY" | jq -r .code 2>/dev/null)"
req testuser "$A" PUT /api/templates/NOT_AN_INTENT '{"providerTemplateName":"x","language":"es","parameterNames":[],"enabled":true}' -H 'If-None-Match: *'
rec $C "unknown intent -> 404 (closed enum)" $([ "$CODE" = 404 ] && echo PASS || echo FAIL) "http=$CODE"
qa_login testowner2; req testowner2 "$B" GET /api/templates -
rec $C "tenant B does not see tenant A mapping" $([ "$(printf '%s' "$BODY" | jq -r --arg i $I '.items[]|select(.templateIntent==$i).mapped')" = false ] && echo PASS || echo FAIL) "B.mapped=$(printf '%s' "$BODY" | jq -r --arg i $I '.items[]|select(.templateIntent==$i).mapped')"
echo "final: $(cur)"
grep -c 'kind=templates' "$STUBDIR/requests.log" | sed 's/^/stub template lookups: /'; grep 'kind=templates' "$STUBDIR/requests.log" | grep -v -c 'token=A' | sed 's/^/lookups with a token other than A: /'
qa_finish
