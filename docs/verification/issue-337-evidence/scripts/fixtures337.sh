#!/usr/bin/env bash
# ABOUTME: Suite #337 fixtures: re-creates the extra synthetic Keycloak users when a Keycloak restart dropped them,
# ABOUTME: re-grants ADMIN (tenant A) and OPERATOR (tenant B) through the real invitation flow, and builds the A/B objects.
set -uo pipefail
. "$(dirname "$0")/qa337.sh"
C=SETUP
# Suite baselines, captured once before any probe: provider sends at the stub and denial audit rows.
[ -n "${STUB0:-}" ] || setid STUB0 "$(stub_sends)"
[ -n "${DENY_BEFORE:-}" ] || setid DENY_BEFORE "$(deny_n)"
T=$(kc_admin_token); [ -n "$T" ] || { echo "Keycloak admin login failed" >&2; exit 1; }
kc_user() { # username first last: create only when missing; the password comes from the 600 file through the environment
  local n code
  n=$(curl -s -H @<(printf 'Authorization: Bearer %s\n' "$T") "$KC/admin/realms/dokene/users?username=$1&exact=true" | jq 'length')
  [ "$n" = 0 ] || { echo "  exists  $1"; return; }
  code=$(QA_PW="$(cat "$EXTRA_PW_FILE")" jq -nc --arg u "$1" --arg f "$2" --arg l "$3" \
    '{username:$u,email:($u+"@dokene.local"),firstName:$f,lastName:$l,enabled:true,emailVerified:true,credentials:[{type:"password",value:$ENV.QA_PW,temporary:false}]}' \
    | curl -s -o /dev/null -w '%{http_code}' -X POST -H @<(printf 'Authorization: Bearer %s\n' "$T") \
      -H 'Content-Type: application/json' "$KC/admin/realms/dokene/users" --data-binary @-)
  echo "  created $1 ($code)"
}
kc_user testadmin QA Admin; kc_user testoperator2 QA OperatorSur; kc_user testnone QA Nobody
for u in testuser testadmin testoperator testviewer testoperator2 testnone; do qa_login "$u" || { echo "login $u failed" >&2; exit 1; }; done
# invite_accept <inviter> <tenant> <invitee> <role>: invite (or reactivate) and accept through the real routes.
invite_accept() {
  local id st; id=$(identity "$3")
  st=$(curl -s -b "$QA_TMP/jar-$1.txt" -H "X-Tenant-Id: $2" "$BFF/api/memberships" | jq -r --arg i "$id" '[.[]|select(.identityId==$i)][0].status // "NONE"')
  case $st in
    NONE) req "$1" "$2" POST /api/memberships "{\"identityId\":\"$id\",\"role\":\"$4\"}"; echo "  invite $3 as $4: $CODE" ;;
    REVOKED) req "$1" "$2" POST "/api/memberships/$id/reactivation" -; echo "  reactivate $3: $CODE" ;;
  esac
  [ "$st" = ACTIVE ] || { req "$3" - POST "/api/tenants/$2/invitation/acceptance" -; echo "  accept $3: $CODE"; }
}
invite_accept testuser "$A" testadmin ADMIN
invite_accept testuser "$B" testoperator2 OPERATOR
for u in testuser testadmin testoperator testviewer; do req "$u" "$A" GET /api/session -; done
rec $C "roles in tenant A: OWNER testuser, ADMIN testadmin, OPERATOR testoperator, VIEWER testviewer; testnone no membership" \
  "$( { [ "$(curl -s -b "$QA_TMP/jar-testuser.txt" -H "X-Tenant-Id: $A" "$BFF/api/memberships" | jq -r --arg a "$(identity testadmin)" --arg o "$(identity testoperator)" --arg v "$(identity testviewer)" '[.[]|select(.status=="ACTIVE" and ((.identityId==$a and .role=="ADMIN") or (.identityId==$o and .role=="OPERATOR") or (.identityId==$v and .role=="VIEWER")))]|length')" = 3 ]; } && echo PASS || echo FAIL)" "tenant=${A:0:8}"
rec $C "tenant B: testuser ADMIN, testoperator2 OPERATOR" \
  "$( { [ "$(curl -s -b "$QA_TMP/jar-testuser.txt" -H "X-Tenant-Id: $B" "$BFF/api/memberships" | jq -r --arg u "$(identity testuser)" --arg o "$(identity testoperator2)" '[.[]|select(.status=="ACTIVE" and ((.identityId==$u and .role=="ADMIN") or (.identityId==$o and .role=="OPERATOR")))]|length')" = 2 ]; } && echo PASS || echo FAIL)" "tenant=${B:0:8}"
# Recorded FAIL lines make the exit status nonzero.
qa_finish
