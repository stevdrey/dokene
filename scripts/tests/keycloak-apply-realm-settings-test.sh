#!/usr/bin/env bash
# ABOUTME: Tests scripts/keycloak-apply-realm-settings.sh against a stubbed `docker` (no daemon, no Keycloak).
# ABOUTME: Covers drift reporting, convergence, idempotence and every failure path. Needs bash and jq.
#
# Usage: ./scripts/tests/keycloak-apply-realm-settings-test.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/keycloak-apply-realm-settings.sh"
LOGOUT_URL=http://oidc-bridge:8080/logout/connect/back-channel/dokene
POLICY='length(12) and notUsername and notEmail'
failures=0

WORK="$(mktemp -d)"
trap '[ -n "$WORK" ] && rm -rf -- "$WORK"' EXIT
mkdir -p "$WORK/bin"

# The stub answers the kcadm calls the script makes (`docker compose exec -T keycloak bash -c <script> kcadm <args>`)
# from files in $STUB_STATE and logs every argv to $STUB_STATE/argv. STUB_FAIL=<op>:<n> fails the n-th call of op
# (lookup, client-read, realm-read, client-update, realm-update, set-password); STUB_IGNORE_UPDATES=1 accepts updates
# without applying them. A set-password call runs the script's own in-container code against the fake kcadm.sh below,
# with this process's DOKENE_TEST_*_PASSWORD variables standing in for the keycloak container's environment.
cat >"$WORK/bin/docker" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail
printf '%s\n' "$*" >>"$STUB_STATE/argv"
container_script="$7"
shift 8
state="$STUB_STATE"
case "$1 $2" in
    "get clients") op=lookup ;;
    "get clients/"*) op=client-read ;;
    "get realms/"*) op=realm-read ;;
    "update clients/"*) op=client-update ;;
    "update realms/"*) op=realm-update ;;
    "set-password "*) op=set-password ;;
    *) echo "stub: unexpected kcadm call: $*" >&2; exit 64 ;;
esac
count=$(( $(cat "$state/calls.$op" 2>/dev/null || echo 0) + 1 ))
echo "$count" >"$state/calls.$op"
if [ "${STUB_FAIL:-}" = "$op:$count" ]; then
    echo "stub: $op failed" >&2
    exit 1
fi
value() { cat "$state/$1" 2>/dev/null; }
case "$op" in
    lookup) echo '[{"id":"client-uuid"}]' ;;
    client-read)
        jq -n --arg url "$(value url)" --arg required "$(value required)" --arg revoke "$(value revoke)" \
            --arg pkce "$(value pkce)" \
            '{id: "client-uuid", clientId: "dokene-bff", redirectUris: ["http://localhost:8080/login/oauth2/code/dokene"],
              attributes: ({"backchannel.logout.url": $url, "backchannel.logout.session.required": $required,
                            "backchannel.logout.revoke.offline.tokens": $revoke,
                            "pkce.code.challenge.method": $pkce} | with_entries(select(.value != "")))}' ;;
    realm-read)
        jq -n --argjson lifespan "$(value lifespan)" --arg policy "$(value policy)" \
            '{ssoSessionMaxLifespan: $lifespan, passwordPolicy: (if $policy == "" then null else $policy end)}' ;;
    client-update|realm-update)
        [ "${STUB_IGNORE_UPDATES:-}" = 1 ] && exit 0
        assignment="${*: -1}"
        case "$assignment" in
            'attributes."backchannel.logout.url"='*) printf '%s' "${assignment#*=}" >"$state/url" ;;
            'attributes."backchannel.logout.session.required"='*) printf '%s' "${assignment#*=}" >"$state/required" ;;
            'attributes."backchannel.logout.revoke.offline.tokens"='*) printf '%s' "${assignment#*=}" >"$state/revoke" ;;
            'attributes."pkce.code.challenge.method"='*) printf '%s' "${assignment#*=}" >"$state/pkce" ;;
            'ssoSessionMaxLifespan='*) printf '%s' "${assignment#*=}" >"$state/lifespan" ;;
            'passwordPolicy='*) printf '%s' "${assignment#*=}" >"$state/policy" ;;
            *) echo "stub: unexpected assignment: $assignment" >&2; exit 64 ;;
        esac ;;
    set-password)
        printf '%s %s\n' "$2" "$3" >>"$state/set-password"
        exec bash -c "${container_script//\/opt\/keycloak\/bin\/kcadm.sh/$STUB_KCADM}" kcadm "$@" ;;
esac
STUB
chmod +x "$WORK/bin/docker"

# The fake kcadm.sh inside the "container": login always works; set-password enforces the 12 character minimum and,
# unlike Keycloak, echoes the refused value, so the test proves the script withholds such a message.
cat >"$WORK/kcadm.sh" <<'FAKE'
#!/usr/bin/env bash
case "$1" in
    config) exit 0 ;;
    set-password)
        # set-password -r <realm> --username <user> --new-password <value> --config <file>
        if [ "${#7}" -lt 12 ]; then
            echo "Invalid password: minimum length 12, got '$7'." >&2
            exit 1
        fi
        printf '%s' "$7" >"$STUB_STATE/password.$5" ;;
    *) echo "fake kcadm.sh: unexpected call: $*" >&2; exit 64 ;;
esac
FAKE
chmod +x "$WORK/kcadm.sh"
export STUB_KCADM="$WORK/kcadm.sh"

# Built at run time: synthetic 32-hex values, kept out of the source so secret scanners have nothing to match.
OWNER_PASSWORD="$(printf '0123456789abcdef%.0s' 1 2)"
OPERATOR_PASSWORD="$(printf 'fedcba9876543210%.0s' 1 2)"
VIEWER_PASSWORD="$(printf '0011223344556677%.0s' 1 2)"

# A realm as imported before issues 307 and 314: session.required set, no logout URL, 10 h SSO max lifespan, no PKCE
# attribute and no password policy. The keycloak container holds the three generated passwords.
fresh_realm() {
    STUB_STATE="$(mktemp -d -p "$WORK")"
    printf 'true' >"$STUB_STATE/required"
    printf '36000' >"$STUB_STATE/lifespan"
    export STUB_STATE
    export DOKENE_TEST_OWNER_PASSWORD="$OWNER_PASSWORD" DOKENE_TEST_OPERATOR_PASSWORD="$OPERATOR_PASSWORD" \
           DOKENE_TEST_VIEWER_PASSWORD="$VIEWER_PASSWORD"
}

run() { PATH="$WORK/bin:$PATH" "$SCRIPT" "$@" >"$WORK/out" 2>&1; }

check() {
    # check "description" command...
    local description="$1"; shift
    if "$@"; then
        printf '[pass] %s\n' "$description"
    else
        printf '[FAIL] %s\n' "$description" >&2
        sed 's/^/       /' "$WORK/out" >&2
        failures=$((failures + 1))
    fi
}

in_sync() {
    [ "$(cat "$STUB_STATE/url")" = "$LOGOUT_URL" ] && [ "$(cat "$STUB_STATE/required")" = true ] \
        && [ "$(cat "$STUB_STATE/revoke")" = false ] && [ "$(cat "$STUB_STATE/lifespan")" = 28800 ] \
        && [ "$(cat "$STUB_STATE/pkce")" = S256 ] && [ "$(cat "$STUB_STATE/policy")" = "$POLICY" ]
}
calls() { cat "$STUB_STATE/calls.$1" 2>/dev/null || echo 0; }
updates() { echo $(( $(calls client-update) + $(calls realm-update) )); }
# No password value in the script's output or on any docker command line it ran.
no_password_leaked() {
    local value
    for value in "$OWNER_PASSWORD" "$OPERATOR_PASSWORD" "$VIEWER_PASSWORD" "${1:-}"; do
        [ -n "$value" ] || continue
        ! grep -qF -- "$value" "$WORK/out" || return 1
        ! grep -qF -- "$value" "$STUB_STATE/argv" || return 1
    done
}

check_reports_drift() {
    fresh_realm
    ! run --check && grep -q '^drift    client backchannel.logout.url' "$WORK/out" \
        && grep -q '^ok       client backchannel.logout.session.required' "$WORK/out" \
        && grep -q '^drift    client pkce.code.challenge.method = <unset> (expected S256)' "$WORK/out" \
        && grep -q "^drift    realm passwordPolicy = <unset> (expected $POLICY)" "$WORK/out" \
        && grep -q '^note     test user passwords are not checkable' "$WORK/out" \
        && [ ! -e "$STUB_STATE/calls.client-update" ] && [ ! -e "$STUB_STATE/calls.realm-update" ] \
        && [ ! -e "$STUB_STATE/calls.set-password" ]
}
check_reports_pkce_drift_alone() {
    fresh_realm
    run && printf 'plain' >"$STUB_STATE/pkce" \
        && ! run --check && grep -q '^drift    client pkce.code.challenge.method = plain (expected S256)' "$WORK/out" \
        && [ "$(grep -c '^drift' "$WORK/out")" -eq 1 ] \
        && run && run --check
}
apply_converges_and_is_idempotent() {
    fresh_realm
    run && in_sync && [ "$(updates)" -eq 5 ] && grep -q 'applied to realm dokene' "$WORK/out" \
        && run && [ "$(updates)" -eq 5 ] && ! grep -q '^updated' "$WORK/out" \
        && run --check
}
apply_sets_each_password_from_its_own_variable_after_the_policy() {
    fresh_realm
    run && [ "$(cat "$STUB_STATE/password.testuser")" = "$OWNER_PASSWORD" ] \
        && [ "$(cat "$STUB_STATE/password.testoperator")" = "$OPERATOR_PASSWORD" ] \
        && [ "$(cat "$STUB_STATE/password.testviewer")" = "$VIEWER_PASSWORD" ] \
        && [ "$(cat "$STUB_STATE/set-password")" = "testuser DOKENE_TEST_OWNER_PASSWORD
testoperator DOKENE_TEST_OPERATOR_PASSWORD
testviewer DOKENE_TEST_VIEWER_PASSWORD" ] \
        && [ "$(grep -n 'realm passwordPolicy' "$WORK/out" | cut -d: -f1)" -lt "$(grep -n '^set ' "$WORK/out" | head -n 1 | cut -d: -f1)" ] \
        && grep -q '^set      password for testviewer from DOKENE_TEST_VIEWER_PASSWORD' "$WORK/out" \
        && no_password_leaked
}
fails_when() {
    # fails_when <op:n> [--check]: that call fails, so the run must fail and say so.
    fresh_realm
    STUB_FAIL="$1" run "${@:2}"
    local status=$?
    [ "$status" -ne 0 ] && grep -q '^Error: ' "$WORK/out"
}
config_failure_exits_1_without_setting_passwords() {
    fresh_realm
    STUB_FAIL=realm-update:1 run
    [ $? -eq 1 ] && [ ! -e "$STUB_STATE/calls.set-password" ]
}
fails_when_updates_do_not_stick() {
    fresh_realm
    ! STUB_IGNORE_UPDATES=1 run && grep -q 'is still' "$WORK/out"
}
password_failure_exits_3() {
    # password_failure_exits_3 <user>: the run still tries every user, names the failed one, and exits 3.
    run
    [ $? -eq 3 ] && grep -qx "Error: could not set password for $1" "$WORK/out" \
        && [ "$(grep -c '^Error: could not set password' "$WORK/out")" -eq 1 ] \
        && [ "$(calls set-password)" -eq 3 ]
}
empty_variable_fails_and_says_to_recreate() {
    fresh_realm
    DOKENE_TEST_OPERATOR_PASSWORD='' password_failure_exits_3 testoperator \
        && grep -q 'DOKENE_TEST_OPERATOR_PASSWORD is empty in the keycloak container; recreate it with ./scripts/dev-env.sh up' "$WORK/out"
}
policy_rejection_names_the_user_never_the_value() {
    fresh_realm
    DOKENE_TEST_VIEWER_PASSWORD=short123 password_failure_exits_3 testviewer \
        && grep -q 'message is withheld' "$WORK/out" && no_password_leaked short123
}
failed_exec_of_the_password_step_exits_3() {
    fresh_realm
    STUB_FAIL=set-password:1 password_failure_exits_3 testuser
}

check "--check reports drift, exits non-zero and changes nothing" check_reports_drift
check "--check reports a drifting PKCE method alone, and apply repairs it" check_reports_pkce_drift_alone
check "apply updates only drifting values once and is then a no-op" apply_converges_and_is_idempotent
check "apply sets each password from its own variable, after the policy, without leaking one" \
    apply_sets_each_password_from_its_own_variable_after_the_policy
check "a failed client lookup fails the run" fails_when lookup:1
check "a failed initial client read fails apply" fails_when client-read:1
check "a failed initial client read fails --check" fails_when client-read:1 --check
check "a failed initial realm read fails apply" fails_when realm-read:1
check "a failed initial realm read fails --check" fails_when realm-read:1 --check
check "a failed update fails the run" fails_when client-update:1
check "a failed config step exits 1 and sets no password" config_failure_exits_1_without_setting_passwords
check "a failed read-back of the client fails the run" fails_when client-read:2
check "a failed read-back of the realm fails the run" fails_when realm-read:2
check "values that do not stick after update fail the run" fails_when_updates_do_not_stick
check "an empty password variable fails with exit 3 and says to recreate the container" \
    empty_variable_fails_and_says_to_recreate
check "a password the policy refuses fails naming the user, never the value" \
    policy_rejection_names_the_user_never_the_value
check "a failed password exec fails with exit 3" failed_exec_of_the_password_step_exits_3

if [ "$failures" -gt 0 ]; then
    echo "$failures check(s) failed" >&2
    exit 1
fi
echo "All checks passed"
