#!/usr/bin/env bash
# ABOUTME: Brings an existing local Keycloak realm up to the settings in dokene-realm.json (ADR 0007, #307, #314).
# ABOUTME: Idempotent; `--check` reports drift without changing anything.
# ==============================================================================
# keycloak-apply-realm-settings.sh
#
# Keycloak's --import-realm skips a realm that already exists, so edits to the
# realm import file never reach a running stack on their own. This script applies
# them to an existing realm:
#   - the session revocation settings of issue #307: the dokene-bff client's
#     back-channel logout attributes and the realm's SSO max lifespan;
#   - the hardening of issue #314: S256 PKCE required on dokene-bff and the
#     realm password policy;
#   - then each test user's password, from that user's variable in the keycloak
#     container's environment (DOKENE_TEST_<ROLE>_PASSWORD, which Compose fills
#     from .env). Passwords are set after the policy, so Keycloak checks them.
#
# Non-destructive and idempotent: it leaves every config value that already
# matches untouched. It runs the Keycloak Admin CLI inside the Compose keycloak
# container with that container's bootstrap admin, so no credential passes
# through the host command line. Passwords are expanded inside the container by
# variable name; the host never sees or prints one.
#
# Usage:
#   ./scripts/keycloak-apply-realm-settings.sh          # apply, print before/after
#   ./scripts/keycloak-apply-realm-settings.sh --check  # report config drift, exit 1 if any
#
# Exit status: 0 success (or no drift with --check), 1 config read or update
# failed (or drift with --check), 2 usage, 3 a test user password was not set.
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
# One line each: scripts/tests/keycloak-realm-config-test.sh reads them and asserts they match the realm JSON.
PKCE_METHOD=S256
PASSWORD_POLICY='length(12) and notUsername and notEmail'
# username:variable in the keycloak container's environment, in the order the passwords are set.
TEST_USERS=(testuser:DOKENE_TEST_OWNER_PASSWORD testoperator:DOKENE_TEST_OPERATOR_PASSWORD
            testviewer:DOKENE_TEST_VIEWER_PASSWORD)
PASSWORD_STEP_FAILED=3

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
        config=/tmp/kcadm-realm-settings.config
        /opt/keycloak/bin/kcadm.sh config credentials --config "$config" --server http://localhost:8080 \
            --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1 \
            || { echo "Error: Keycloak admin login failed." >&2; exit 1; }
        /opt/keycloak/bin/kcadm.sh "$@" --config "$config"
    ' kcadm "$@" </dev/null 2> >(sed '/^$/d' >&2))  # kcadm writes a bare newline to stderr on every call
}

# kcadm_set_password USERNAME VARIABLE: sets USERNAME's password to the value of VARIABLE in the keycloak container's
# environment. kcadm() would put the value on the host command line, so only the two names cross it here and the
# value is expanded inside the container: it is visible only in the container's process list while kcadm.sh
# set-password runs. Keycloak's own error is passed on unless it contains the value. Same argv prefix as kcadm(), so
# the test stub intercepts both.
kcadm_set_password() {
    (cd "$ROOT_DIR" && docker compose exec -T keycloak bash -c '
        config=/tmp/kcadm-realm-settings.config
        user="$2" variable="$3" realm="$4"
        password="${!variable:-}"
        if [ -z "$password" ]; then
            echo "Error: $variable is empty in the keycloak container; recreate it with ./scripts/dev-env.sh up." >&2
            exit 1
        fi
        /opt/keycloak/bin/kcadm.sh config credentials --config "$config" --server http://localhost:8080 \
            --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1 \
            || { echo "Error: Keycloak admin login failed." >&2; exit 1; }
        if ! output="$(/opt/keycloak/bin/kcadm.sh set-password -r "$realm" --username "$user" \
                --new-password "$password" --config "$config" 2>&1)"; then
            case "$output" in
                *"$password"*) output="Keycloak refused the password (its message is withheld because it contains the value)." ;;
            esac
            printf "%s\n" "$output" | sed "/^$/d" >&2
            exit 1
        fi
    ' kcadm set-password "$1" "$2" "$REALM" </dev/null)
}

client_uuid="$(kcadm get clients -r "$REALM" -q "clientId=$CLIENT_ID" --fields id | jq -r '.[0].id // empty')" \
    || { echo "Error: could not look up client $CLIENT_ID in realm $REALM." >&2; exit 1; }
[ -n "$client_uuid" ] || { echo "Error: client $CLIENT_ID not found in realm $REALM." >&2; exit 1; }

# Prints one line per setting: name|current|wanted|kcadm path|kcadm assignment. Fails, printing nothing usable, when
# any read fails or returns something that is not the expected JSON; errexit does not reach into command
# substitutions, so every step checks its own status.
current_state() {
    local client attributes realm realm_lifespan realm_policy
    # The whole client: --fields drops attribute keys.
    client="$(kcadm get "clients/$client_uuid" -r "$REALM")" || return 1
    attributes="$(jq -ec '.attributes // {} | objects' <<<"$client")" || return 1
    realm="$(kcadm get "realms/$REALM" --fields ssoSessionMaxLifespan,passwordPolicy)" || return 1
    realm_lifespan="$(jq -er '.ssoSessionMaxLifespan // 0 | numbers' <<<"$realm")" || return 1
    # A realm without a policy has none (null or absent), which reads as unset.
    realm_policy="$(jq -er '.passwordPolicy // "" | strings' <<<"$realm")" || return 1
    attr() { jq -r --arg key "$1" '.[$key] // ""' <<<"$attributes"; }
    printf '%s|%s|%s|%s|%s\n' \
        "client backchannel.logout.url" "$(attr backchannel.logout.url)" "$LOGOUT_URL" \
            "clients/$client_uuid" "attributes.\"backchannel.logout.url\"=$LOGOUT_URL" \
        "client backchannel.logout.session.required" "$(attr backchannel.logout.session.required)" "true" \
            "clients/$client_uuid" 'attributes."backchannel.logout.session.required"=true' \
        "client backchannel.logout.revoke.offline.tokens" "$(attr backchannel.logout.revoke.offline.tokens)" "false" \
            "clients/$client_uuid" 'attributes."backchannel.logout.revoke.offline.tokens"=false' \
        "client pkce.code.challenge.method" "$(attr pkce.code.challenge.method)" "$PKCE_METHOD" \
            "clients/$client_uuid" "attributes.\"pkce.code.challenge.method\"=$PKCE_METHOD" \
        "realm ssoSessionMaxLifespan" "$realm_lifespan" "$MAX_LIFESPAN" \
            "realms/$REALM" "ssoSessionMaxLifespan=$MAX_LIFESPAN" \
        "realm passwordPolicy" "$realm_policy" "$PASSWORD_POLICY" \
            "realms/$REALM" "passwordPolicy=$PASSWORD_POLICY"
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
    echo "note     test user passwords are not checkable; a normal run sets them from the keycloak container."
    [ "$drift" -eq 0 ] || exit 1
    exit 0
elif [ "$drift" -gt 0 ]; then
    # Read back so the run proves what Keycloak now holds.
    read_state updated
    while IFS='|' read -r name current wanted _ _; do
        [ "$current" = "$wanted" ] || { echo "Error: $name is still ${current:-<unset>}." >&2; exit 1; }
    done <<<"$state"
    echo "Realm settings applied to realm $REALM."
fi

# Every run sets the passwords: Keycloak cannot report whether a stored password equals a given value.
password_failures=0
for entry in "${TEST_USERS[@]}"; do
    user="${entry%%:*}"
    if kcadm_set_password "$user" "${entry#*:}"; then
        echo "set      password for $user from ${entry#*:}"
    else
        echo "Error: could not set password for $user" >&2
        password_failures=$((password_failures + 1))
    fi
done
[ "$password_failures" -eq 0 ] || exit "$PASSWORD_STEP_FAILED"
