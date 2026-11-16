#!/usr/bin/env bash
# ABOUTME: Ends every Keycloak session of one synthetic user (admin API); the BFF gets a back-channel logout.
. "$(dirname "${BASH_SOURCE[0]}")/qa333.sh"
T=$(printf '%s' "$KC_BOOTSTRAP_ADMIN_PASSWORD" | curl -s -d grant_type=password -d client_id=admin-cli -d "username=$KC_BOOTSTRAP_ADMIN_USERNAME" --data-urlencode "password@-" "$KC/realms/master/protocol/openid-connect/token" | jq -r .access_token)
U=$(curl -s -H @<(printf 'Authorization: Bearer %s\n' "$T") "$KC/admin/realms/dokene/users?username=$1&exact=true" | jq -r '.[0].id')
curl -s -o /dev/null -w "keycloak logout $1: %{http_code}\n" -X POST -H @<(printf 'Authorization: Bearer %s\n' "$T") "$KC/admin/realms/dokene/users/$U/logout"
