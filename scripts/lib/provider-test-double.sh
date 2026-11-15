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
#                                            with the synthetic key, or differs from its baseline in any other way
#                                            that could change that: it stops the backend and returns 3
#
#   PROVIDER_TEST_COMPOSE_FILE   the compose file whose backend service is the baseline (default compose.yaml)
#   PROVIDER_TEST_DOCKERFILE     the Dockerfile whose final stage is the image's baseline
#                                (default infra/docker/backend/Dockerfile)
#   PROVIDER_TEST_EXTRA_NAMES    space-separated extra environment names a script's own override adds; each must
#                                pass the same reserved-name rules as override extras
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

# _provider_test_norm_into VAR TEXT: sets VAR to TEXT in upper case with every character other than A-Z and 0-9
# removed, the way Spring's relaxed binding compares names (DOKENE_AI_OPENAI_APIKEY, dokene.ai.openai.api-key and
# DOKENE_AI_OPENAI_API_KEY are one key). In-process (no subshell) and bash 3.2 compatible: explicit letter lists, not
# ranges, so the locale cannot change what matches.
_provider_test_norm_into() {
    local _pt_in="$2" _pt_out='' _pt_c _pt_i=0 _pt_head
    local _pt_lower=abcdefghijklmnopqrstuvwxyz _pt_upper=ABCDEFGHIJKLMNOPQRSTUVWXYZ
    while [ "$_pt_i" -lt "${#_pt_in}" ]; do
        _pt_c="${_pt_in:$_pt_i:1}"; _pt_i=$((_pt_i + 1))
        case "$_pt_c" in
            [ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789]) _pt_out="$_pt_out$_pt_c" ;;
            [abcdefghijklmnopqrstuvwxyz])
                _pt_head="${_pt_lower%%"$_pt_c"*}"; _pt_out="$_pt_out${_pt_upper:${#_pt_head}:1}" ;;
        esac
    done
    printf -v "$1" '%s' "$_pt_out"
}

# _provider_test_reserved NAME: true when an environment variable named NAME could set the provider, its endpoint or
# its key, under any spelling Spring binds, could load configuration that does (SPRING_APPLICATION_JSON,
# SPRING_CONFIG_*, JVM option variables carrying -D system properties), or could load other code (LD_*, LOADER_*,
# CLASSPATH, BASH_ENV, ENV, PATH, JAVA_HOME). Both override extras and the declared extra names of the backend check
# go through it, so the two refuse the same names.
_provider_test_reserved() {
    local n
    case "$1" in
        LD_* | LOADER_* | CLASSPATH | BASH_ENV | ENV | PATH | JAVA_HOME) return 0 ;;
    esac
    _provider_test_norm_into n "$1"
    case "$n" in
        DOKENEAIOPENAI*APIKEY* | DOKENEAIOPENAI*BASEURL* | DOKENEAIOPENAI*TRUSTEDHOSTS* | DOKENEAIPROVIDER* \
            | SPRINGAPPLICATIONJSON* | SPRINGCONFIG* | *JAVAOPTIONS* | *JAVATOOLOPTIONS*) return 0 ;;
    esac
    return 1
}

provider_test_override() {
    local file="$1" url="$2" extra="${3:-}" line name
    [ -n "$file" ] || { _provider_test_refuse "no override file given"; return 2; }
    provider_test_url_ok "$url" \
        || { _provider_test_refuse "refusing a provider test destination that is not a loopback URL; no override written"; return 2; }
    # Extra environment is one `      NAME: "value"` line per variable with a literal value, so it cannot add YAML keys
    # of its own (env_file, secrets, volumes, command), a second spelling of a reserved variable, or a `$` that Compose
    # would fill in from the host environment or .env (a real key under an unreserved name such as the model).
    while IFS= read -r line; do
        [ -z "$line" ] && continue
        if ! [[ "$line" =~ ^\ {6}([A-Z][A-Z0-9_]*):\ \"[^\"\\$]*\"$ ]]; then
            _provider_test_refuse "extra environment must be indented NAME: \"value\" lines with literal values (no \$); no override written"
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

# _provider_test_compose_names FILE and _provider_test_compose_mounts FILE: the environment names and the secret
# target paths of the backend service, read from the compose file's text. No compose command runs, so no value from
# .env is read.
_provider_test_compose_names() {
    awk '/^  backend:$/ { b = 1; next } b && /^  [^ ]/ { b = 0 }
         b && /^    environment:$/ { e = 1; next } b && e && /^    [^ ]/ { e = 0 }
         b && e && /^      [A-Za-z_][A-Za-z0-9_]*:/ { sub(/^ +/, ""); sub(/:.*/, ""); print }' "$1"
}
_provider_test_compose_mounts() {
    awk '/^  backend:$/ { b = 1; next } b && /^  [^ ]/ { b = 0 }
         b && /^ +target: \// { sub(/^ +target: */, ""); print }' "$1"
}

# _provider_test_dockerfile_baseline FILE: the final stage of the backend Dockerfile as ENTRYPOINT, CMD and WORKDIR
# lines in the JSON form docker inspect prints, and one ENV line per variable it sets. A form the parser does not
# know (shell-form ENTRYPOINT or CMD, ENV without "NAME=\"value\"") prints an UNPARSED line, so the check fails closed.
_provider_test_dockerfile_baseline() {
    awk '/^FROM / { ep = ""; cmd = "null"; wd = ""; n = 0; bad = 0; next }
         /^WORKDIR / { wd = $2; next }
         /^ENTRYPOINT / { v = $0; sub(/^ENTRYPOINT +/, "", v); gsub(/, +/, ",", v); if (v !~ /^\[/) bad = 1; ep = v; next }
         /^CMD / { v = $0; sub(/^CMD +/, "", v); gsub(/, +/, ",", v); if (v !~ /^\[/) bad = 1; cmd = v; next }
         /^ENV / { v = $0; sub(/^ENV +/, "", v)
                   if (v ~ /^[A-Za-z_][A-Za-z0-9_]*="[^"]*"$/) { sub(/="/, "=", v); sub(/"$/, "", v); env[++n] = v }
                   else bad = 1
                   next }
         END { if (bad || ep == "" || wd == "") print "UNPARSED"
               print "ENTRYPOINT " ep; print "CMD " cmd; print "WORKDIR \"" wd "\""
               for (i = 1; i <= n; i++) print "ENV " env[i] }' "$1"
}

# The names the digest-pinned base image (eclipse-temurin, see invariant 34) gives the backend image. Together with
# the Dockerfile's own ENV names they are the only variables the image may carry.
PROVIDER_TEST_BASE_IMAGE_NAMES='PATH JAVA_HOME JAVA_VERSION LANG LANGUAGE LC_ALL'

# _provider_test_backend_state VAR_CONTAINER VAR_IMAGE: sets the first variable to the running backend container's
# entrypoint, cmd and working directory (JSON, one line each), environment entries (ENV NAME=value) and mount
# destinations (MOUNT path), and the second to the same lines for its image (no mounts). They are two separate
# inspections, so a multi-line container value can never add lines to the image's baseline. Values travel on a pipe,
# never on a command line.
_provider_test_backend_state() {
    local _pt_id _pt_image _pt_out _pt_fmt='ENTRYPOINT {{json .Config.Entrypoint}}{{"\n"}}CMD {{json .Config.Cmd}}{{"\n"}}WORKDIR {{json .Config.WorkingDir}}{{"\n"}}{{range .Config.Env}}ENV {{println .}}{{end}}'
    _pt_id="$(docker compose ps -q backend 2>/dev/null)" || return 1
    [ -n "$_pt_id" ] || return 1
    _pt_image="$(docker inspect --format '{{.Image}}' "$_pt_id" 2>/dev/null)" || return 1
    [ -n "$_pt_image" ] || return 1
    _pt_out="$(docker inspect --format "$_pt_fmt"'{{range .Mounts}}MOUNT {{println .Destination}}{{end}}' \
        "$_pt_id" 2>/dev/null)" || return 1
    [ -n "$_pt_out" ] || return 1
    printf -v "$1" '%s' "$_pt_out"
    _pt_out="$(docker image inspect --format "$_pt_fmt" "$_pt_image" 2>/dev/null)" || return 1
    [ -n "$_pt_out" ] || return 1
    printf -v "$2" '%s' "$_pt_out"; _pt_out=''
}

# The backend is compared with its baseline, not searched for known bad settings: anything the baseline does not
# allow fails closed.
# - The image itself is checked against this tree: its entrypoint, cmd and working directory must be the backend
#   Dockerfile's, every variable the Dockerfile sets must have the Dockerfile's value, and the image may carry no
#   variable beyond those and PROVIDER_TEST_BASE_IMAGE_NAMES. A modified image cannot bring other settings along.
# - Environment names: the backend's environment block in the compose file, the names declared in
#   PROVIDER_TEST_EXTRA_NAMES (each must pass _provider_test_reserved), and the image's own names, which must keep the
#   image's value (PATH, JAVA_HOME, JAVA_TOOL_OPTIONS and the locale). Any other name fails closed, so another spelling
#   of the key or base URL, SPRING_APPLICATION_JSON, SPRING_CONFIG_*, JDK_JAVA_OPTIONS, LD_PRELOAD, BASH_ENV and the
#   like never reach the backend unseen.
# - Mounts: only the backend's secret targets in the compose file.
# - Entrypoint, cmd and working directory: exactly the image's, so no -D option, command-line argument or other
#   ./config directory is added.
# - A line that is none of the above is the continuation of a multi-line value and fails closed, as does an
#   environment entry without '='.
# - Values: the canonical key must be the synthetic one, the canonical base URL a loopback URL, and a JVM option
#   variable may not load agents, argument files or configuration (defence in depth for the image's own options).
provider_test_assert_backend() {
    local compose="${PROVIDER_TEST_COMPOSE_FILE:-compose.yaml}" nl='
'
    local dockerfile="${PROVIDER_TEST_DOCKERFILE:-infra/docker/backend/Dockerfile}" df_line df_entrypoint='' df_cmd=''
    local df_workdir='' df_env='' df_names='' base_names="$nl${PROVIDER_TEST_BASE_IMAGE_NAMES// /$nl}$nl"
    local state='' image_state='' line kind entry name value n extra reason='' key_seen='' url_seen=''
    local allowed='' mounts='' image_env='' image_names='' entrypoint='' cmd='' workdir=''
    local image_entrypoint='' image_cmd='' image_workdir=''
    if [ ! -r "$compose" ]; then
        reason="the compose file $compose is not readable"
    else
        allowed="$nl$(_provider_test_compose_names "$compose")$nl"
        mounts="$nl$(_provider_test_compose_mounts "$compose")$nl"
        [ "$allowed" = "$nl$nl" ] && reason="no backend environment found in $compose"
    fi
    if [ -z "$reason" ] && [ ! -r "$dockerfile" ]; then
        reason="the Dockerfile $dockerfile is not readable"
    elif [ -z "$reason" ]; then
        while IFS= read -r df_line; do
            case "$df_line" in
                UNPARSED) reason="the Dockerfile $dockerfile has a form the check cannot read" ;;
                'ENTRYPOINT '*) df_entrypoint="${df_line#ENTRYPOINT }" ;;
                'CMD '*) df_cmd="${df_line#CMD }" ;;
                'WORKDIR '*) df_workdir="${df_line#WORKDIR }" ;;
                'ENV '*) df_env="$df_env$nl${df_line#ENV }"; df_line="${df_line#ENV }"; df_names="$df_names$nl${df_line%%=*}" ;;
            esac
        done <<< "$(_provider_test_dockerfile_baseline "$dockerfile")"
        df_env="$df_env$nl"; df_names="$df_names$nl"
    fi
    for extra in ${PROVIDER_TEST_EXTRA_NAMES:-}; do
        [ -n "$reason" ] && break
        if ! [[ "$extra" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || _provider_test_reserved "$extra"; then
            reason="PROVIDER_TEST_EXTRA_NAMES may not declare $extra"
        else
            allowed="$allowed$extra$nl"
        fi
    done
    if [ -z "$reason" ]; then
        _provider_test_backend_state state image_state || reason='no running backend container or image'
    fi
    # The image's baseline.
    while [ -z "$reason" ] && IFS= read -r line; do
        [ -z "$line" ] && continue
        kind="${line%% *}"; entry="${line#* }"
        case "$kind" in
            ENTRYPOINT) image_entrypoint="$entry" ;;
            CMD) image_cmd="$entry" ;;
            WORKDIR) image_workdir="$entry" ;;
            ENV)
                image_env="$image_env$nl$entry"; name="${entry%%=*}"; image_names="$image_names$nl$name"
                case "$df_names" in
                    *"$nl$name$nl"*)
                        case "$df_env" in
                            *"$nl$entry$nl"*) ;;
                            *) reason="$name of the backend image differs from its Dockerfile" ;;
                        esac ;;
                    *)
                        case "$base_names" in
                            *"$nl$name$nl"*) ;;
                            *) reason="the backend image sets $name, which its Dockerfile and base image do not" ;;
                        esac ;;
                esac ;;
            *) reason='an environment value of the backend image spans several lines' ;;
        esac
    done <<< "$image_state"
    image_env="$image_env$nl"; image_names="$image_names$nl"
    [ -z "$reason" ] && [ -z "$image_entrypoint" ] && reason='the backend image could not be inspected'
    if [ -z "$reason" ] && { [ "$image_entrypoint" != "$df_entrypoint" ] || [ "$image_cmd" != "$df_cmd" ] \
        || [ "$image_workdir" != "$df_workdir" ]; }; then
        reason='the backend image entrypoint, command or working directory differs from its Dockerfile'
    fi
    # The container against that baseline.
    while [ -z "$reason" ] && IFS= read -r line; do
        [ -z "$line" ] && continue
        kind="${line%% *}"; entry="${line#* }"
        case "$kind" in
            ENTRYPOINT) entrypoint="$entry"; [ "$entry" = "$image_entrypoint" ] \
                || reason='the backend entrypoint differs from its image' ;;
            CMD) cmd="$entry"; [ "$entry" = "$image_cmd" ] || reason='the backend command differs from its image' ;;
            WORKDIR) workdir="$entry"; [ "$entry" = "$image_workdir" ] \
                || reason='the backend working directory differs from its image' ;;
            MOUNT)
                case "$mounts" in
                    *"$nl$entry$nl"*) ;;
                    *) reason="a mount is not part of the backend's compose definition ($entry)" ;;
                esac ;;
            ENV)
                case "$entry" in
                    *=*) ;;
                    *) reason='an environment entry of the backend has no value separator'; continue ;;
                esac
                name="${entry%%=*}"; value="${entry#*=}"
                case "$allowed" in
                    *"$nl$name$nl"*) ;;
                    *)
                        case "$image_names" in
                            *"$nl$name$nl"*)
                                case "$image_env" in
                                    *"$nl$entry$nl"*) ;;
                                    *) reason="$name of the backend differs from its image" ;;
                                esac ;;
                            *) reason="$name is set on the backend but is not part of its compose definition" ;;
                        esac ;;
                esac
                [ -n "$reason" ] && continue
                case "$name" in
                    DOKENE_AI_OPENAI_API_KEY)
                        key_seen=1
                        [ "$value" = "$PROVIDER_TEST_API_KEY" ] \
                            || reason="$name of the backend is not the synthetic test key" ;;
                    DOKENE_AI_OPENAI_BASE_URL)
                        url_seen=1
                        provider_test_url_ok "$value" || reason="$name of the backend is not a loopback test double" ;;
                    *JAVA_OPTIONS | *JAVA_TOOL_OPTIONS)
                        case " $value" in
                            *" @"* | *"	@"*) reason="$name of the backend reads a JVM argument file" ;;
                        esac
                        _provider_test_norm_into n "$value"
                        case "$n" in
                            *DOKENEAI* | *SPRINGAPPLICATIONJSON* | *SPRINGCONFIG* | *VMOPTIONSFILE* | *XXFLAGS* \
                                | *JAVAAGENT* | *AGENTPATH* | *AGENTLIB* | *XRUN* | *XBOOTCLASSPATH* | *PATCHMODULE* \
                                | *LOADER* | *XXONERROR* | *XXONOUTOFMEMORYERROR*)
                                reason="$name of the backend loads code or configuration" ;;
                        esac ;;
                esac ;;
            *) reason='an environment value of the backend spans several lines' ;;
        esac
    done <<< "$state"
    value=''; entry=''; line=''; state=''; image_state=''; image_env=''
    if [ -z "$reason" ] && { [ -z "$entrypoint" ] || [ -z "$cmd" ] || [ -z "$workdir" ]; }; then
        reason='the backend entrypoint, command or working directory could not be inspected'
    elif [ -z "$reason" ] && [ -z "$key_seen" ]; then
        reason='DOKENE_AI_OPENAI_API_KEY of the backend is not the synthetic test key'
    elif [ -z "$reason" ] && [ -z "$url_seen" ]; then
        reason='DOKENE_AI_OPENAI_BASE_URL of the backend is not a loopback test double'
    fi
    [ -z "$reason" ] && return 0
    printf 'provider-test-double: %s; stopping the backend before any provider call\n' "$reason" >&2
    docker compose stop backend >/dev/null 2>&1
    return 3
}
