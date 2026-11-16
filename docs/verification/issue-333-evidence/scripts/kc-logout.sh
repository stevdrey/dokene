#!/usr/bin/env bash
# ABOUTME: Ends every Keycloak session of one synthetic user (admin API); the BFF gets a back-channel logout.
. "$(dirname "${BASH_SOURCE[0]}")/qa333.sh"
T=$(kc_admin_token); [ -n "$T" ] || { echo "Keycloak admin login failed" >&2; exit 1; }
U=$(curl -s -H @<(printf 'Authorization: Bearer %s\n' "$T") "$KC/admin/realms/dokene/users?username=$1&exact=true" | jq -r '.[0].id // empty')
[ -n "$U" ] || { echo "no Keycloak user named $1" >&2; exit 1; }
curl -s -o /dev/null -w "keycloak logout $1: %{http_code}\n" -X POST -H @<(printf 'Authorization: Bearer %s\n' "$T") "$KC/admin/realms/dokene/users/$U/logout"
