#!/usr/bin/env bash
# Tests for scripts/keycloak-apply-session-revocation.sh against a stubbed `docker` (no daemon, no Keycloak).
# Needs bash and jq.
#
# Usage: ./scripts/tests/keycloak-apply-session-revocation-test.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/keycloak-apply-session-revocation.sh"
LOGOUT_URL=http://oidc-bridge:8080/logout/connect/back-channel/dokene
failures=0

WORK="$(mktemp -d)"
trap '[ -n "$WORK" ] && rm -rf -- "$WORK"' EXIT
mkdir -p "$WORK/bin"

# The stub answers the kcadm calls the script makes (`docker compose exec -T keycloak bash -c <script> kcadm <args>`)
# from files in $STUB_STATE. STUB_FAIL=<op>:<n> fails the n-th call of op (lookup, client-read, realm-read,
# client-update, realm-update); STUB_IGNORE_UPDATES=1 accepts updates without applying them.
cat >"$WORK/bin/docker" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail
shift 8
state="$STUB_STATE"
case "$1 $2" in
    "get clients") op=lookup ;;
    "get clients/"*) op=client-read ;;
    "get realms/"*) op=realm-read ;;
    "update clients/"*) op=client-update ;;
    "update realms/"*) op=realm-update ;;
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
            '{id: "client-uuid", clientId: "dokene-bff", redirectUris: ["http://localhost:8080/login/oauth2/code/dokene"],
              attributes: ({"backchannel.logout.url": $url, "backchannel.logout.session.required": $required,
                            "backchannel.logout.revoke.offline.tokens": $revoke} | with_entries(select(.value != "")))}' ;;
    realm-read) jq -n --argjson lifespan "$(value lifespan)" '{ssoSessionMaxLifespan: $lifespan}' ;;
    client-update|realm-update)
        [ "${STUB_IGNORE_UPDATES:-}" = 1 ] && exit 0
        assignment="${*: -1}"
        case "$assignment" in
            'attributes."backchannel.logout.url"='*) printf '%s' "${assignment#*=}" >"$state/url" ;;
            'attributes."backchannel.logout.session.required"='*) printf '%s' "${assignment#*=}" >"$state/required" ;;
            'attributes."backchannel.logout.revoke.offline.tokens"='*) printf '%s' "${assignment#*=}" >"$state/revoke" ;;
            'ssoSessionMaxLifespan='*) printf '%s' "${assignment#*=}" >"$state/lifespan" ;;
            *) echo "stub: unexpected assignment: $assignment" >&2; exit 64 ;;
        esac ;;
esac
STUB
chmod +x "$WORK/bin/docker"

# A realm as imported before issue 307: session.required set, no logout URL, 10 h SSO max lifespan.
fresh_realm() {
    STUB_STATE="$(mktemp -d -p "$WORK")"
    printf 'true' >"$STUB_STATE/required"
    printf '36000' >"$STUB_STATE/lifespan"
    export STUB_STATE
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
        && [ "$(cat "$STUB_STATE/revoke")" = false ] && [ "$(cat "$STUB_STATE/lifespan")" = 28800 ]
}
calls() { cat "$STUB_STATE/calls.$1" 2>/dev/null || echo 0; }
updates() { echo $(( $(calls client-update) + $(calls realm-update) )); }

check_reports_drift() {
    fresh_realm
    ! run --check && grep -q '^drift    client backchannel.logout.url' "$WORK/out" \
        && grep -q '^ok       client backchannel.logout.session.required' "$WORK/out" \
        && [ ! -e "$STUB_STATE/calls.client-update" ]
}
apply_converges_and_is_idempotent() {
    fresh_realm
    run && in_sync && [ "$(updates)" -eq 3 ] && grep -q 'applied to realm dokene' "$WORK/out" \
        && run && [ "$(updates)" -eq 3 ] && ! grep -q '^updated' "$WORK/out" \
        && run --check
}
fails_when() {
    # fails_when <op:n> [--check]: that call fails, so the run must fail and say so.
    fresh_realm
    STUB_FAIL="$1" run "${@:2}"
    local status=$?
    [ "$status" -ne 0 ] && grep -q '^Error: ' "$WORK/out"
}
fails_when_updates_do_not_stick() {
    fresh_realm
    ! STUB_IGNORE_UPDATES=1 run && grep -q 'is still' "$WORK/out"
}

check "--check reports drift, exits non-zero and changes nothing" check_reports_drift
check "apply updates only drifting values once and is then a no-op" apply_converges_and_is_idempotent
check "a failed client lookup fails the run" fails_when lookup:1
check "a failed initial client read fails apply" fails_when client-read:1
check "a failed initial client read fails --check" fails_when client-read:1 --check
check "a failed initial realm read fails apply" fails_when realm-read:1
check "a failed initial realm read fails --check" fails_when realm-read:1 --check
check "a failed update fails the run" fails_when client-update:1
check "a failed read-back of the client fails the run" fails_when client-read:2
check "a failed read-back of the realm fails the run" fails_when realm-read:2
check "values that do not stick after update fail the run" fails_when_updates_do_not_stick

if [ "$failures" -gt 0 ]; then
    echo "$failures check(s) failed" >&2
    exit 1
fi
echo "All checks passed"
