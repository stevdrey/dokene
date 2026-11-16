#!/usr/bin/env bash
# ABOUTME: P3-MQA-ENV-02 membership probes on the running stack: invite/accept, role change takes effect, revoke
# ABOUTME: removes access, reactivation, and a dual-tenant member for tenant selection. Status codes only.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa333.sh"
. "$OUTD/ids"
for u in testuser testowner2 testnone; do qa_login $u; done
NONE=$(identity testnone); TU=$(identity testuser)
C=ENV-02
req testnone "$A" GET /api/customers -;           rec $C "non-member reads tenant A customers" $([ "$CODE" = 403 ] || [ "$CODE" = 404 ] && echo PASS || echo FAIL) "http=$CODE rid=$(rid)"
req testuser "$A" POST /api/memberships "{\"identityId\":\"$NONE\",\"role\":\"VIEWER\"}"; rec $C "OWNER invites testnone as VIEWER" $([ "$CODE" = 201 ] && echo PASS || echo FAIL) "http=$CODE"
req testnone "$A" GET /api/customers -;           rec $C "INVITED (not accepted) member reads customers" $([ "$CODE" = 403 ] || [ "$CODE" = 404 ] && echo PASS || echo FAIL) "http=$CODE"
req testnone - POST "/api/tenants/$A/invitation/acceptance" -; rec $C "invitee accepts" $([ "$CODE" = 200 ] && echo PASS || echo FAIL) "http=$CODE"
req testnone "$A" GET /api/customers -;           rec $C "VIEWER reads customers" $([ "$CODE" = 200 ] && echo PASS || echo FAIL) "http=$CODE"
req testnone "$A" POST /api/customers '{"displayName":"QA333 Probe Viewer","phones":[{"number":"+56983339991","region":"CL","primary":true}]}'
rec $C "VIEWER cannot create customer" $([ "$CODE" = 403 ] && echo PASS || echo FAIL) "http=$CODE"
req testuser "$A" PUT "/api/memberships/$NONE/role" '{"role":"OPERATOR"}'; rec $C "OWNER changes role to OPERATOR" $([ "$CODE" = 204 ] && echo PASS || echo FAIL) "http=$CODE"
req testnone "$A" POST /api/customers '{"displayName":"QA333 Probe Operator","phones":[{"number":"+56983339992","region":"CL","primary":true}]}'
PROBE=$(printf '%s' "$BODY" | jq -r '.id // empty')
rec $C "role change takes effect on the same session (OPERATOR creates)" $([ "$CODE" = 201 ] && echo PASS || echo FAIL) "http=$CODE"
req testuser "$A" DELETE "/api/memberships/$NONE" -; rec $C "OWNER revokes membership" $([ "$CODE" = 204 ] || [ "$CODE" = 200 ] && echo PASS || echo FAIL) "http=$CODE"
req testnone "$A" GET /api/customers -;           rec $C "revoked member loses access" $([ "$CODE" = 403 ] || [ "$CODE" = 404 ] && echo PASS || echo FAIL) "http=$CODE"
req testuser "$A" POST "/api/memberships/$NONE/reactivation" -; rec $C "OWNER reactivates (back to INVITED)" $([ "$CODE" = 200 ] && echo PASS || echo FAIL) "http=$CODE status=$(printf '%s' "$BODY" | jq -r '.status // empty')"
req testnone "$A" GET /api/customers -;           rec $C "reactivated but not accepted still denied" $([ "$CODE" = 403 ] || [ "$CODE" = 404 ] && echo PASS || echo FAIL) "http=$CODE"
req testuser "$A" DELETE "/api/memberships/$NONE" -; rec $C "cleanup: revoke testnone again" NOTE "http=$CODE"
if [ -n "$PROBE" ]; then
  req testuser "$A" GET "/api/customers/$PROBE" -
  req testuser "$A" DELETE "/api/customers/$PROBE" - -H "If-Match: $(etag)"
  rec $C "cleanup: archive probe customer (with If-Match)" "$([ "$CODE" = 204 ] && echo NOTE || echo FAIL)" "http=$CODE"
fi
# dual-tenant member for tenant selection (ENV-06): testuser becomes ADMIN of tenant B
cur=$(curl -s -b "$QA_TMP/jar-testowner2.txt" -H "X-Tenant-Id: $B" "$BFF/api/memberships" | jq -r --arg i "$TU" '[.[]|select(.identityId==$i)][0].status // ""')
if [ -z "$cur" ]; then req testowner2 "$B" POST /api/memberships "{\"identityId\":\"$TU\",\"role\":\"ADMIN\"}"; rec $C "tenant B OWNER invites testuser as ADMIN" $([ "$CODE" = 201 ] && echo PASS || echo FAIL) "http=$CODE"; cur=INVITED; fi
[ "$cur" = INVITED ] && { req testuser - POST "/api/tenants/$B/invitation/acceptance" -; rec $C "testuser accepts tenant B" $([ "$CODE" = 200 ] && echo PASS || echo FAIL) "http=$CODE"; }
n=$(curl -s -b "$QA_TMP/jar-testuser.txt" "$BFF/api/tenants" | jq 'length'); rec $C "testuser lists both workspaces" $([ "$n" = 2 ] && echo PASS || echo FAIL) "count=$n"
req testuser "$A" GET /api/memberships -; ra=$(printf '%s' "$BODY" | jq -r --arg i "$TU" '.[]|select(.identityId==$i).role')
req testuser "$B" GET /api/memberships -; rb=$(printf '%s' "$BODY" | jq -r --arg i "$TU" '.[]|select(.identityId==$i).role')
rec $C "per-tenant role resolved from X-Tenant-Id" $([ "$ra" = OWNER ] && [ "$rb" = ADMIN ] && echo PASS || echo FAIL) "A=$ra B=$rb"
req testuser "$A" GET /api/customers -; na=$(printf '%s' "$BODY" | jq -r '[.customers[].displayName] | sort | join(",")')
req testuser "$B" GET /api/customers -; nb=$(printf '%s' "$BODY" | jq -r '[.customers[].displayName] | sort | join(",")')
# Tenant A holds the four QA333 A fixtures, tenant B only its own; neither list contains the other's names.
iso=FAIL
case ",$na," in *",QA333 Ana Vencida,"*) case ",$nb," in *",QA333 Elena Sur Vencida,"*)
  case ",$na," in *Elena*) ;; *) case ",$nb," in *Ana*) ;; *) iso=PASS ;; esac ;; esac ;; esac ;; esac
rec $C "customer lists are tenant scoped (disjoint names)" "$iso" "A=$(printf '%s' "$na" | tr ',' '\n' | grep -c .) B=$(printf '%s' "$nb" | tr ',' '\n' | grep -c .)"
req testuser - GET /api/customers -; rec $C "no tenant selected -> refused" $([ "${CODE:0:1}" = 4 ] && echo PASS || echo FAIL) "http=$CODE code=$(printf '%s' "$BODY" | jq -r '.code // empty' 2>/dev/null)"
