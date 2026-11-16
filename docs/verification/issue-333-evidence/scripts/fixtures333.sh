#!/usr/bin/env bash
# ABOUTME: Suite #333 fixtures (P3-MQA-ENV-02): four extra synthetic Keycloak users, tenant B "QA Café Sur", the
# ABOUTME: ADMIN membership in tenant A and OWNER/OPERATOR in tenant B through the real invitation flow. Idempotent.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa333.sh"

echo "== 1. Keycloak users"
KC_TOKEN=$(printf '%s' "$KC_BOOTSTRAP_ADMIN_PASSWORD" | curl -s -d grant_type=password -d client_id=admin-cli \
  -d "username=$KC_BOOTSTRAP_ADMIN_USERNAME" --data-urlencode "password@-" "$KC/realms/master/protocol/openid-connect/token" | jq -r .access_token)
[ -n "$KC_TOKEN" ] && [ "$KC_TOKEN" != null ] || { echo "Keycloak admin login failed" >&2; exit 1; }
kc_user() { # username first last
  local n; n=$(curl -s -H @<(printf 'Authorization: Bearer %s\n' "$KC_TOKEN") "$KC/admin/realms/dokene/users?username=$1&exact=true" | jq 'length')
  if [ "$n" = 0 ]; then
    local code; code=$(QA_PW="$(cat "$EXTRA_PW_FILE")" jq -nc --arg u "$1" --arg f "$2" --arg l "$3" \
      '{username:$u,email:($u+"@dokene.local"),firstName:$f,lastName:$l,enabled:true,emailVerified:true,credentials:[{type:"password",value:$ENV.QA_PW,temporary:false}]}' \
      | curl -s -o /dev/null -w '%{http_code}' -X POST -H @<(printf 'Authorization: Bearer %s\n' "$KC_TOKEN") \
        -H 'Content-Type: application/json' "$KC/admin/realms/dokene/users" --data-binary @-)
    echo "  created $1 ($code)"
  else echo "  exists  $1"; fi
}
kc_user testadmin QA Admin; kc_user testowner2 QA OwnerSur; kc_user testoperator2 QA OperatorSur; kc_user testnone QA Nobody

echo "== 2. logins"
for u in testuser testadmin testoperator testviewer testowner2 testoperator2 testnone; do
  qa_login $u && printf '  %-14s ok\n' $u || { echo "  login $u FAILED" >&2; exit 1; }
done

echo "== 3. tenants"
A=$(curl -s -b "$QA_TMP/jar-testuser.txt" "$BFF/api/tenants" | jq -r '[.[]|select(.displayName=="QA Café Norte")][0].tenantId // empty')
B=$(curl -s -b "$QA_TMP/jar-testowner2.txt" "$BFF/api/tenants" | jq -r '[.[]|select(.displayName=="QA Café Sur")][0].tenantId // empty')
if [ -z "$B" ]; then
  req testowner2 - POST /api/tenants '{"displayName":"QA Café Sur","idempotencyKey":"qa333-tenant-b"}' -H 'Idempotency-Key: qa333-tenant-b'
  echo "  provision B: $CODE"; B=$(printf '%s' "$BODY" | jq -r .tenantId)
fi
echo "  A=$A B=$B"

invite_accept() { # owner tenant invitee role
  local id; id=$(identity "$3")
  local cur; cur=$(curl -s -b "$QA_TMP/jar-$1.txt" -H "X-Tenant-Id: $2" "$BFF/api/memberships" | jq -r --arg i "$id" '[.[]|select(.identityId==$i)][0] | "\(.role // "")/\(.status // "")"')
  if [ "$cur" = "/" ]; then req "$1" "$2" POST /api/memberships "{\"identityId\":\"$id\",\"role\":\"$4\"}"; echo "  invite $3 as $4: $CODE"; cur="$4/INVITED"; fi
  if [ "${cur#*/}" = INVITED ]; then req "$3" - POST "/api/tenants/$2/invitation/acceptance" -; echo "  accept $3: $CODE"; else echo "  $3 is $cur"; fi
}
invite_accept testuser "$A" testadmin ADMIN
invite_accept testowner2 "$B" testoperator2 OPERATOR

cat > "$OUTD/ids" <<EOF
A=$A
B=$B
EOF
echo "== done"
