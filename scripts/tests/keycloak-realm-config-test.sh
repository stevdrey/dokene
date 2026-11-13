#!/usr/bin/env bash
# ABOUTME: Static checks on the local Keycloak realm import file (issue #314, audit L9); needs bash and jq, no Docker.
# ABOUTME: Guards PKCE S256, the password policy, per user password placeholders and the settings the audit found sound.
#
# Usage: ./scripts/tests/keycloak-realm-config-test.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REALM_FILE="$ROOT_DIR/infra/docker/keycloak/import/dokene-realm.json"
APPLY_SCRIPT="$ROOT_DIR/scripts/keycloak-apply-realm-settings.sh"
EXPECTED_POLICY='length(12) and notUsername and notEmail'
failures=0

command -v jq >/dev/null 2>&1 || { echo "Error: jq is required." >&2; exit 1; }

WORK="$(mktemp -d)"
trap '[ -n "$WORK" ] && rm -rf -- "$WORK"' EXIT

# script_constant NAME: the value of the one line `NAME=value` or `NAME='value'` in the apply script.
script_constant() {
    sed -n "s/^$1=//p" "$APPLY_SCRIPT" | head -n 1 | sed "s/^'\(.*\)'\$/\1/"
}

# realm_violations FILE: prints one line per broken rule; prints nothing for a compliant realm.
realm_violations() {
    jq -r --arg policy "$EXPECTED_POLICY" '
        def client: (.clients // [] | map(select(.clientId == "dokene-bff")) | .[0]) // {};
        def expect(cond; message): if cond then empty else message end;
        def placeholder(role): "${env.DOKENE_TEST_" + role + "_PASSWORD}";
        def password_of(name): [.users // [] | .[] | select(.username == name) | .credentials // [] | .[]
                                | select(.type == "password") | .value];
        expect(client.attributes["pkce.code.challenge.method"] == "S256"; "dokene-bff does not require PKCE S256"),
        expect(.passwordPolicy == $policy; "passwordPolicy is not \($policy)"),
        expect(client.publicClient == false and client.clientAuthenticatorType == "client-secret"
               and client.secret == "${env.DOKENE_OIDC_CLIENT_SECRET}"; "dokene-bff is not a confidential client"),
        expect(client.implicitFlowEnabled == false; "dokene-bff allows the implicit flow"),
        expect(client.directAccessGrantsEnabled == false; "dokene-bff allows direct access grants"),
        expect(.registrationAllowed == false; "self registration is allowed"),
        expect(.bruteForceProtected == true; "brute force protection is off"),
        expect(.sslRequired == "external"; "sslRequired is not external"),
        expect(password_of("testuser") == [placeholder("OWNER")]; "testuser password is not the OWNER placeholder"),
        expect(password_of("testoperator") == [placeholder("OPERATOR")];
               "testoperator password is not the OPERATOR placeholder"),
        expect(password_of("testviewer") == [placeholder("VIEWER")]; "testviewer password is not the VIEWER placeholder"),
        (.users // [] | .[] | .username as $name | .credentials // [] | .[]
         | select(.type == "password" and ((.value // "") | test("^\\$\\{env\\.DOKENE_TEST_[A-Z]+_PASSWORD\\}$") | not))
         | "user \($name) has a literal password")
    ' "$1"
}

check() {
    # check "description" command...
    local description="$1"; shift
    if "$@"; then
        printf '[pass] %s\n' "$description"
    else
        printf '[FAIL] %s\n' "$description" >&2
        [ -s "$WORK/out" ] && sed 's/^/       /' "$WORK/out" >&2
        failures=$((failures + 1))
    fi
}

hardened_file_passes() {
    realm_violations "$REALM_FILE" >"$WORK/out" 2>&1 && [ ! -s "$WORK/out" ]
}
script_policy_matches_the_realm() {
    : >"$WORK/out"
    [ "$(script_constant PASSWORD_POLICY)" = "$(jq -r '.passwordPolicy' "$REALM_FILE")" ] \
        && [ "$(script_constant PASSWORD_POLICY)" = "$EXPECTED_POLICY" ]
}
script_pkce_matches_the_realm() {
    : >"$WORK/out"
    [ "$(script_constant PKCE_METHOD)" = "$(jq -r '.clients[] | select(.clientId == "dokene-bff")
        | .attributes["pkce.code.challenge.method"]' "$REALM_FILE")" ]
}
# fails_on "jq edit" "expected violation": a copy of the realm with that edit must report that violation.
fails_on() {
    jq "$1" "$REALM_FILE" >"$WORK/mutated.json" || return 1
    realm_violations "$WORK/mutated.json" >"$WORK/out" 2>&1
    grep -qF -- "$2" "$WORK/out"
}

check "the hardened realm file passes every rule" hardened_file_passes
check "the apply script's PASSWORD_POLICY equals the realm's passwordPolicy" script_policy_matches_the_realm
check "the apply script's PKCE_METHOD equals the realm's dokene-bff attribute" script_pkce_matches_the_realm
check "a realm without the PKCE attribute fails" fails_on \
    '(.clients[] | select(.clientId == "dokene-bff") | .attributes) |= del(.["pkce.code.challenge.method"])' \
    "does not require PKCE S256"
check "a realm with PKCE plain fails" fails_on \
    '(.clients[] | select(.clientId == "dokene-bff") | .attributes["pkce.code.challenge.method"]) = "plain"' \
    "does not require PKCE S256"
check "a realm with another password policy fails" fails_on '.passwordPolicy = "length(8)"' "passwordPolicy is not"
check "a realm with a literal password fails" fails_on \
    '(.users[] | select(.username == "testviewer") | .credentials[0].value) = "a-literal-value"' \
    "user testviewer has a literal password"
# shellcheck disable=SC2016  # a Keycloak placeholder, not a shell expansion
check "a realm whose users share one placeholder fails" fails_on \
    '(.users[] | select(.username == "testuser") | .credentials[0].value) = "${env.DOKENE_TEST_VIEWER_PASSWORD}"' \
    "testuser password is not the OWNER placeholder"
check "a public client fails" fails_on \
    '(.clients[] | select(.clientId == "dokene-bff") | .publicClient) = true' "is not a confidential client"
check "the implicit flow fails" fails_on \
    '(.clients[] | select(.clientId == "dokene-bff") | .implicitFlowEnabled) = true' "allows the implicit flow"
check "direct access grants fail" fails_on \
    '(.clients[] | select(.clientId == "dokene-bff") | .directAccessGrantsEnabled) = true' "allows direct access grants"
check "self registration fails" fails_on '.registrationAllowed = true' "self registration is allowed"
check "brute force protection off fails" fails_on '.bruteForceProtected = false' "brute force protection is off"
check "sslRequired none fails" fails_on '.sslRequired = "none"' "sslRequired is not external"

if [ "$failures" -gt 0 ]; then
    echo "$failures check(s) failed" >&2
    exit 1
fi
echo "All checks passed"
