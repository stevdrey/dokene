#!/usr/bin/env bash
# ABOUTME: Builds the suite #149 fixture set on the local stack: four extra Keycloak users, tenant B, the memberships,
# ABOUTME: the stub backed stack, and the verified integrations and approved template mappings of both tenants.
#
# Run from the repository root with the stack up (./scripts/dev-env.sh up --seed). Idempotent. Synthetic data only.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"
set -a; . ./.env; set +a
QA149_DIR=${QA149_DIR:-docs/verification/issue-149-evidence}
export OUTD=${OUTD:-$QA149_DIR/out}; export STUBDIR=${STUBDIR:-$QA149_DIR/stub}; export QA_TMP=${QA_TMP:-$(mktemp -d)}
mkdir -p "$OUTD" "$STUBDIR"
. "$QA149_DIR/scripts/qa149.sh"
for t in curl jq openssl javac docker; do command -v $t >/dev/null || { echo "missing tool: $t" >&2; exit 1; }; done
KC=http://localhost:${KEYCLOAK_PORT:-8081}

echo "== 1. Keycloak users (admin API, realm dokene)"
KC_TOKEN=$(curl -s -d grant_type=password -d client_id=admin-cli -d "username=$KC_BOOTSTRAP_ADMIN_USERNAME" \
  --data-urlencode "password=$KC_BOOTSTRAP_ADMIN_PASSWORD" "$KC/realms/master/protocol/openid-connect/token" | jq -r .access_token)
[ -n "$KC_TOKEN" ] && [ "$KC_TOKEN" != null ] || { echo "Keycloak admin login failed" >&2; exit 1; }
kc_user() { # username first last
  local existing; existing=$(curl -s -H "Authorization: Bearer $KC_TOKEN" "$KC/admin/realms/dokene/users?username=$1&exact=true" | jq 'length')
  if [ "$existing" = 0 ]; then
    local code; code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $KC_TOKEN" -H 'Content-Type: application/json' \
      "$KC/admin/realms/dokene/users" -d "$(jq -nc --arg u "$1" --arg f "$2" --arg l "$3" --arg p "$PW" \
      '{username:$u,email:($u+"@dokene.local"),firstName:$f,lastName:$l,enabled:true,emailVerified:true,credentials:[{type:"password",value:$p,temporary:false}]}')")
    echo "  created $1 ($code)"
  else echo "  exists  $1"; fi
}
kc_user testadmin QA Admin; kc_user testowner2 QA OwnerSur; kc_user testoperator2 QA OperatorSur; kc_user testnone QA Nobody

echo "== 2. sessions and identities"
for u in testuser testadmin testoperator testviewer testowner2 testoperator2 testnone; do qa_login $u; printf '  %-14s %s\n' $u "$(identity $u)"; done
ID_A_OWNER=$(identity testuser); ID_A_ADMIN=$(identity testadmin); ID_A_OPERATOR=$(identity testoperator); ID_A_VIEWER=$(identity testviewer)
ID_B_OWNER=$(identity testowner2); ID_B_OPERATOR=$(identity testoperator2); ID_X_NONE=$(identity testnone)

echo "== 3. tenants"
A=$(curl -s -b "$QA_TMP/jar-testuser.txt" "$BFF/api/tenants" | jq -r '[.[]|select(.displayName=="QA Café Norte")][0].tenantId // empty')
[ -n "$A" ] || { echo "tenant A (QA Café Norte) not found; run ./scripts/dev-env.sh up --seed" >&2; exit 1; }
B=$(curl -s -b "$QA_TMP/jar-testowner2.txt" "$BFF/api/tenants" | jq -r '[.[]|select(.displayName=="QA Café Sur")][0].tenantId // empty')
if [ -z "$B" ]; then
  req testowner2 - POST /api/tenants '{"displayName":"QA Café Sur","idempotencyKey":"qa149-tenant-b"}'
  [ "$CODE" = 201 ] || [ "$CODE" = 200 ] || { echo "tenant B provisioning failed: $CODE $BODY" >&2; exit 1; }
  B=$(echo "$BODY" | jq -r .tenantId)
fi
echo "  A=$A"; echo "  B=$B"

echo "== 4. memberships"
member() { # owner tenant identity role
  local cur; cur=$(getj "$1" "$2" /api/memberships | jq -r --arg i "$3" '[.[]|select(.identityId==$i)][0] | "\(.role // "")/\(.status // "")"')
  case $cur in
    "$4/ACTIVE") echo "  ok      $3 is $4";;
    "/") req "$1" "$2" POST /api/memberships "{\"identityId\":\"$3\",\"role\":\"$4\"}"; echo "  added   $3 as $4 ($CODE)";;
    */ACTIVE) req "$1" "$2" PUT "/api/memberships/$3/role" "{\"role\":\"$4\"}"; echo "  changed $3 to $4 ($CODE)";;
    *) echo "  WARN    $3 is $cur in tenant $2 (revoked memberships cannot be reactivated)";;
  esac
}
member testuser "$A" "$ID_A_ADMIN" ADMIN
member testowner2 "$B" "$ID_B_OPERATOR" OPERATOR

PN_A=100000000000001; PN_B=100000000000002; WABA_A=200000000000001; WABA_B=200000000000002
cat > "$OUTD/ids" <<EOF
A=$A
B=$B
ID_A_OWNER=$ID_A_OWNER
ID_A_ADMIN=$ID_A_ADMIN
ID_A_OPERATOR=$ID_A_OPERATOR
ID_A_VIEWER=$ID_A_VIEWER
ID_B_OWNER=$ID_B_OWNER
ID_B_OPERATOR=$ID_B_OPERATOR
ID_X_NONE=$ID_X_NONE
PN_A=$PN_A
PN_B=$PN_B
WABA_A=$WABA_A
WABA_B=$WABA_B
EOF
echo "  wrote $OUTD/ids"

echo "== 5. stub + override + backend restart"
"$QA149_DIR/scripts/stack149.sh" up || exit 1
for u in testuser testadmin testoperator testviewer testowner2 testoperator2 testnone; do qa_login $u; done

echo "== 6. integrations IA and IB (verified) and templates TA and TB (approved)"
integration() { # manager tenant pn waba ref
  req "$1" "$2" GET /api/integrations/whatsapp -; local v; v=$(etag)
  local body; body=$(jq -nc --arg p "$3" --arg w "$4" --arg r "$5" '{phoneNumberId:$p,businessAccountId:$w,displayPhoneNumber:"+50600000000",enabled:true,secretRef:$r}')
  if [ "$CODE" = 200 ] && [ -n "$v" ]; then req "$1" "$2" PUT /api/integrations/whatsapp "$body" -H "If-Match: $v"; else req "$1" "$2" PUT /api/integrations/whatsapp "$body" -H 'If-None-Match: *'; fi
  echo "  PUT integration tenant $2: $CODE $(echo "$BODY" | jq -c '{enabled,outboundEnabled,webhookVerified,version}' 2>/dev/null)"
  if [ "$(echo "$BODY" | jq -r .webhookVerified)" != true ]; then
    req "$1" "$2" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: $(etag)"
    echo "  ownership proof tenant $2: $CODE $(echo "$BODY" | jq -c '{webhookVerified,version}' 2>/dev/null)"
  fi
  if [ "$(echo "$BODY" | jq -r .outboundEnabled)" != true ]; then
    req "$1" "$2" PUT /api/integrations/whatsapp/outbound '{"enabled":true}' -H "If-Match: $(etag)"
    echo "  tenant switch on tenant $2: $CODE"
  fi
}
template() { # writer tenant name
  local v; v=$(tmpl_version "$1" "$2")
  local body; body=$(jq -nc --arg n "$3" '{providerTemplateName:$n,language:"es",parameterNames:["customer_name","message_body"],enabled:true}')
  if [ -n "$v" ] && [ "$v" != null ]; then req "$1" "$2" PUT /api/templates/GENERAL_FOLLOW_UP "$body" -H "If-Match: \"$v\""; else req "$1" "$2" PUT /api/templates/GENERAL_FOLLOW_UP "$body" -H 'If-None-Match: *'; fi
  echo "  PUT template tenant $2: $CODE $(echo "$BODY" | jq -c '{mapped,enabled,approvalStatus,sendable,version}' 2>/dev/null)"
  stub_set templates approved
  req "$1" "$2" POST /api/templates/GENERAL_FOLLOW_UP/approval-refresh - -H "If-Match: $(etag)"
  echo "  approval refresh tenant $2: $CODE $(echo "$BODY" | jq -c '{approvalStatus,sendable,version}' 2>/dev/null)"
}
integration testuser "$A" "$PN_A" "$WABA_A" qa149-token-a
integration testowner2 "$B" "$PN_B" "$WABA_B" qa149-token-b
template testuser "$A" qa149_general_follow_up
template testowner2 "$B" qa149_general_follow_up
echo "== fixtures ready. Next: run the sec149-*.sh scripts (see README.md)."
