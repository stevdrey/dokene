#!/usr/bin/env bash
# ==============================================================================
# keycloak-apply-session-revocation.sh
#
# Applies the session revocation settings of issue #307 (ADR 0007) to a realm that
# already exists. Keycloak's --import-realm skips existing realms, so a Keycloak
# whose realm predates #307 keeps working without back-channel logout until this
# runs; revalidation still bounds revocation meanwhile.
#
# Non-destructive and idempotent: it changes only the dokene-bff client's
# back-channel logout attributes and the realm's SSO max lifespan, and leaves
# every value that already matches untouched. It runs the Keycloak Admin CLI
# inside the Compose keycloak container with that container's bootstrap admin,
# so no credential passes through the host command line.
#
# Usage:
#   ./scripts/keycloak-apply-session-revocation.sh          # apply, print before/after
#   ./scripts/keycloak-apply-session-revocation.sh --check  # report drift, exit 1 if any
#
# Environment (defaults match infra/docker/keycloak/import/dokene-realm.json):
#   DOKENE_KEYCLOAK_BACK_CHANNEL_LOGOUT_URL  URL Keycloak posts logout tokens to
#   DOKENE_KEYCLOAK_SSO_MAX_LIFESPAN_SECONDS SSO session max lifespan (seconds)
# ==============================================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REALM=dokene
CLIENT_ID=dokene-bff
LOGOUT_URL="${DOKENE_KEYCLOAK_BACK_CHANNEL_LOGOUT_URL:-http://oidc-bridge:8080/logout/connect/back-channel/dokene}"
MAX_LIFESPAN="${DOKENE_KEYCLOAK_SSO_MAX_LIFESPAN_SECONDS:-28800}"

CHECK_ONLY=false
case "${1:-}" in
    "") ;;
    --check) CHECK_ONLY=true ;;
    *) echo "Usage: $0 [--check]" >&2; exit 2 ;;
esac

command -v jq >/dev/null 2>&1 || { echo "Error: jq is required." >&2; exit 1; }

# Runs kcadm.sh in the keycloak container, logged in as the container's bootstrap admin. Stdin is closed so that
# `docker compose exec` cannot consume the input of the loops below.
kcadm() {
    (cd "$ROOT_DIR" && docker compose exec -T keycloak bash -c '
        config=/tmp/kcadm-session-revocation.config
        /opt/keycloak/bin/kcadm.sh config credentials --config "$config" --server http://localhost:8080 \
            --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1 \
            || { echo "Error: Keycloak admin login failed." >&2; exit 1; }
        /opt/keycloak/bin/kcadm.sh "$@" --config "$config"
    ' kcadm "$@" </dev/null 2> >(sed '/^$/d' >&2))  # kcadm writes a bare newline to stderr on every call
}

client_uuid="$(kcadm get clients -r "$REALM" -q "clientId=$CLIENT_ID" --fields id | jq -r '.[0].id // empty')" \
    || { echo "Error: could not look up client $CLIENT_ID in realm $REALM." >&2; exit 1; }
[ -n "$client_uuid" ] || { echo "Error: client $CLIENT_ID not found in realm $REALM." >&2; exit 1; }

# Prints one line per setting: name|current|wanted|kcadm path|kcadm assignment. Fails, printing nothing usable, when
# any read fails or returns something that is not the expected JSON; errexit does not reach into command
# substitutions, so every step checks its own status.
current_state() {
    local client attributes realm_lifespan
    # The whole client: --fields drops attribute keys.
    client="$(kcadm get "clients/$client_uuid" -r "$REALM")" || return 1
    attributes="$(jq -ec '.attributes // {} | objects' <<<"$client")" || return 1
    realm_lifespan="$(kcadm get "realms/$REALM" --fields ssoSessionMaxLifespan \
        | jq -er '.ssoSessionMaxLifespan // 0 | numbers')" || return 1
    attr() { jq -r --arg key "$1" '.[$key] // ""' <<<"$attributes"; }
    printf '%s|%s|%s|%s|%s\n' \
        "client backchannel.logout.url" "$(attr backchannel.logout.url)" "$LOGOUT_URL" \
            "clients/$client_uuid" "attributes.\"backchannel.logout.url\"=$LOGOUT_URL" \
        "client backchannel.logout.session.required" "$(attr backchannel.logout.session.required)" "true" \
            "clients/$client_uuid" 'attributes."backchannel.logout.session.required"=true' \
        "client backchannel.logout.revoke.offline.tokens" "$(attr backchannel.logout.revoke.offline.tokens)" "false" \
            "clients/$client_uuid" 'attributes."backchannel.logout.revoke.offline.tokens"=false' \
        "realm ssoSessionMaxLifespan" "$realm_lifespan" "$MAX_LIFESPAN" \
            "realms/$REALM" "ssoSessionMaxLifespan=$MAX_LIFESPAN"
}

# Captured before parsing: a loop reading a failed command's output would see no settings and report success.
read_state() {
    state="$(current_state)" || { echo "Error: could not read the $1 state of realm $REALM." >&2; exit 1; }
}

read_state current
drift=0
while IFS='|' read -r name current wanted path assignment; do
    if [ "$current" = "$wanted" ]; then
        echo "ok       $name = $current"
        continue
    fi
    drift=$((drift + 1))
    if [ "$CHECK_ONLY" = "true" ]; then
        echo "drift    $name = ${current:-<unset>} (expected $wanted)"
    else
        if [ "$path" = "realms/$REALM" ]; then
            kcadm update "$path" -s "$assignment" >/dev/null || { echo "Error: could not update $name." >&2; exit 1; }
        else
            kcadm update "$path" -r "$REALM" -s "$assignment" >/dev/null \
                || { echo "Error: could not update $name." >&2; exit 1; }
        fi
        echo "updated  $name: ${current:-<unset>} -> $wanted"
    fi
done <<<"$state"

if [ "$CHECK_ONLY" = "true" ]; then
    [ "$drift" -eq 0 ] || exit 1
elif [ "$drift" -gt 0 ]; then
    # Read back so the run proves what Keycloak now holds.
    read_state updated
    while IFS='|' read -r name current wanted _ _; do
        [ "$current" = "$wanted" ] || { echo "Error: $name is still ${current:-<unset>}." >&2; exit 1; }
    done <<<"$state"
    echo "Session revocation settings applied to realm $REALM."
fi
