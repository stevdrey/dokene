# shellcheck shell=bash
# ABOUTME: Points the local backend at an AI provider test double (fault-injection stub, refused or slow port) with a
# ABOUTME: synthetic API key only, and stops the backend before any call when a real key could reach it (issue 329).
#
#   provider_test_url_ok URL                 true for an http(s) loopback URL (localhost, 127.0.0.1, [::1]) only
#   provider_test_override FILE URL [YAML]   writes a Compose override: provider openai, base URL URL, the synthetic
#                                            key, plus the extra backend environment lines YAML (already indented)
#   provider_test_export URL                 exports the same base URL and synthetic key for `dev-env.sh` restarts
#   provider_test_unexport                   clears both again, so the next restart reads the key from .env
#   provider_test_assert_backend             fails closed when the running backend is not on a loopback test double
#                                            with the synthetic key: it stops the backend and returns 3
#
# A provider failure test only needs a destination that answers or fails the way the case asks for, never a real
# credential. The OpenAI endpoint policy (issue 322) accepts any loopback port over plain HTTP, so it cannot tell a
# test double from a local proxy that forwards the key; this library makes sure a test double only ever gets
# PROVIDER_TEST_API_KEY. Messages name the variables and never print a configured key.

PROVIDER_TEST_API_KEY='sk-dokene-test-double-not-a-real-key'

provider_test_url_ok() {
    local url="$1"
    # One line, no whitespace or userinfo; scheme, exact loopback host, optional port, optional path.
    [[ "$url" =~ ^https?://(localhost|127\.0\.0\.1|\[::1\])(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?$ ]]
}

_provider_test_refuse() {
    printf 'provider-test-double: %s\n' "$1" >&2
    return 2
}

# _provider_test_norm TEXT: upper case with every character other than A-Z and 0-9 removed, the way Spring's relaxed
# binding compares names (DOKENE_AI_OPENAI_APIKEY, dokene.ai.openai.api-key and DOKENE_AI_OPENAI_API_KEY are one key).
_provider_test_norm() {
    local text="${1^^}"
    printf '%s' "${text//[^A-Z0-9]/}"
}

# _provider_test_reserved NAME: true when an environment variable named NAME could set the provider, its endpoint or
# its key, under any spelling Spring binds, or could load configuration that does (SPRING_APPLICATION_JSON,
# SPRING_CONFIG_*, JVM option variables carrying -D system properties).
_provider_test_reserved() {
    local n
    n="$(_provider_test_norm "$1")"
    case "$n" in
        *OPENAI* | *APIKEY* | *BASEURL* | *TRUSTEDHOSTS* | DOKENEAIPROVIDER* \
            | SPRINGAPPLICATIONJSON* | SPRINGCONFIG* | *JAVAOPTIONS* | *JAVATOOLOPTIONS*) return 0 ;;
    esac
    return 1
}

provider_test_override() {
    local file="$1" url="$2" extra="${3:-}" line name
    [ -n "$file" ] || { _provider_test_refuse "no override file given"; return 2; }
    provider_test_url_ok "$url" \
        || { _provider_test_refuse "refusing a provider test destination that is not a loopback URL; no override written"; return 2; }
    # Extra environment is one `      NAME: "value"` line per variable, so it cannot add YAML keys of its own
    # (env_file, secrets, volumes, command) or a second spelling of a reserved variable.
    while IFS= read -r line; do
        [ -z "$line" ] && continue
        if ! [[ "$line" =~ ^\ {6}([A-Z][A-Z0-9_]*):\ \"[^\"\\]*\"$ ]]; then
            _provider_test_refuse "extra environment must be indented NAME: \"value\" lines; no override written"
            return 2
        fi
        name="${BASH_REMATCH[1]}"
        if _provider_test_reserved "$name"; then
            _provider_test_refuse "the extra environment may not set the provider, its endpoint or key, or load configuration ($name); no override written"
            return 2
        fi
    done <<< "$extra"
    {
        printf 'services:\n  backend:\n    environment:\n'
        printf '      DOKENE_AI_PROVIDER: "openai"\n'
        printf '      DOKENE_AI_OPENAI_BASE_URL: "%s"\n' "$url"
        printf '      DOKENE_AI_OPENAI_API_KEY: "%s"\n' "$PROVIDER_TEST_API_KEY"
        if [ -n "$extra" ]; then printf '%s\n' "$extra"; fi
    } > "$file"
}

provider_test_export() {
    provider_test_url_ok "$1" \
        || { _provider_test_refuse "refusing a provider test destination that is not a loopback URL; nothing exported"; return 2; }
    export DOKENE_AI_OPENAI_BASE_URL="$1" DOKENE_AI_OPENAI_API_KEY="$PROVIDER_TEST_API_KEY"
}

provider_test_unexport() {
    unset DOKENE_AI_OPENAI_API_KEY
    export DOKENE_AI_OPENAI_BASE_URL=
}

# _provider_test_backend_state: prints the running backend container's environment entries (ENV NAME=value) and mount
# destinations (MOUNT path). Values travel on a pipe, never on a command line.
_provider_test_backend_state() {
    local id
    id="$(docker compose ps -q backend 2>/dev/null)" || return 1
    [ -n "$id" ] || return 1
    docker inspect --format '{{range .Config.Env}}ENV {{println .}}{{end}}{{range .Mounts}}MOUNT {{println .Destination}}{{end}}' \
        "$id" 2>/dev/null
}

# Every spelling of the key and the base URL that Spring binds is checked, not only the canonical name: each key
# alias must hold the synthetic key and each base URL alias a loopback URL, and both canonical names must be present.
# Configuration a test double cannot vouch for fails closed: SPRING_APPLICATION_JSON or SPRING_CONFIG_* set at all,
# JVM options that set a provider or configuration property, a mounted config tree file for the provider.
provider_test_assert_backend() {
    local state line kind entry name value n reason='' keys=0 urls=0
    state="$(_provider_test_backend_state)" || reason='no running backend container'
    [ -n "$state" ] || reason='no running backend container'
    while [ -z "$reason" ] && IFS= read -r line; do
        kind="${line%% *}"; entry="${line#* }"
        if [ "$kind" = MOUNT ]; then
            case "$entry" in /run/secrets/*)
                _provider_test_reserved "${entry##*/}" && reason="a mounted secret file can set the provider configuration ($entry)" ;;
            esac
            continue
        fi
        [ "$kind" = ENV ] || continue
        name="${entry%%=*}"; value="${entry#*=}"; n="$(_provider_test_norm "$name")"
        case "$n" in
            DOKENEAIOPENAI*APIKEY*)
                [ "$name" = DOKENE_AI_OPENAI_API_KEY ] && keys=$((keys + 1))
                [ "$value" = "$PROVIDER_TEST_API_KEY" ] \
                    || reason="$name of the backend is not the synthetic test key" ;;
            DOKENEAIOPENAI*BASEURL*)
                [ "$name" = DOKENE_AI_OPENAI_BASE_URL ] && urls=$((urls + 1))
                provider_test_url_ok "$value" || reason="$name of the backend is not a loopback test double" ;;
            SPRINGAPPLICATIONJSON* | SPRINGCONFIG*)
                [ -n "$value" ] && reason="$name is set on the backend and could configure the provider" ;;
            *JAVAOPTIONS* | *JAVATOOLOPTIONS*)
                case "$(_provider_test_norm "$value")" in
                    *DOKENEAI* | *SPRINGAPPLICATIONJSON* | *SPRINGCONFIG*)
                        reason="$name of the backend sets provider or configuration properties" ;;
                esac ;;
        esac
    done <<< "$state"
    value=''; entry=''; line=''; state=''
    if [ -z "$reason" ] && [ "$keys" -ne 1 ]; then
        reason='DOKENE_AI_OPENAI_API_KEY of the backend is not the synthetic test key'
    elif [ -z "$reason" ] && [ "$urls" -ne 1 ]; then
        reason='DOKENE_AI_OPENAI_BASE_URL of the backend is not a loopback test double'
    fi
    [ -z "$reason" ] && return 0
    printf 'provider-test-double: %s; stopping the backend before any provider call\n' "$reason" >&2
    docker compose stop backend >/dev/null 2>&1
    return 3
}
