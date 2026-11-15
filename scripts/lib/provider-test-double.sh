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

provider_test_override() {
    local file="$1" url="$2" extra="${3:-}"
    [ -n "$file" ] || { _provider_test_refuse "no override file given"; return 2; }
    provider_test_url_ok "$url" \
        || { _provider_test_refuse "refusing a provider test destination that is not a loopback URL; no override written"; return 2; }
    case "$extra" in
        *DOKENE_AI_OPENAI_API_KEY* | *DOKENE_AI_OPENAI_BASE_URL* | *DOKENE_AI_PROVIDER*)
            _provider_test_refuse "the extra environment may not set the provider, base URL or key; no override written"
            return 2 ;;
    esac
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

# _provider_test_backend_env NAME: prints the value of NAME in the running backend container's environment. The
# value travels on a pipe, never on a command line.
_provider_test_backend_env() {
    local id
    id="$(docker compose ps -q backend 2>/dev/null)" || return 1
    [ -n "$id" ] || return 1
    docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$id" 2>/dev/null \
        | awk -v name="$1" 'index($0, name "=") == 1 { print substr($0, length(name) + 2); exit }'
}

provider_test_assert_backend() {
    local url key reason=''
    url="$(_provider_test_backend_env DOKENE_AI_OPENAI_BASE_URL)" || reason='no running backend container'
    key="$(_provider_test_backend_env DOKENE_AI_OPENAI_API_KEY)"
    if [ -z "$reason" ] && ! provider_test_url_ok "$url"; then
        reason='DOKENE_AI_OPENAI_BASE_URL of the backend is not a loopback test double'
    elif [ -z "$reason" ] && [ "$key" != "$PROVIDER_TEST_API_KEY" ]; then
        reason='DOKENE_AI_OPENAI_API_KEY of the backend is not the synthetic test key'
    fi
    key=''
    [ -z "$reason" ] && return 0
    printf 'provider-test-double: %s; stopping the backend before any provider call\n' "$reason" >&2
    docker compose stop backend >/dev/null 2>&1
    return 3
}
