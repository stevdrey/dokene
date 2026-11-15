#!/usr/bin/env bash
# Offline tests for scripts/lib/provider-test-double.sh (issue 329): provider failure tests reach only a loopback test
# double, with the synthetic key only, and the backend is stopped before any call when that does not hold. A stub
# docker on PATH stands in for the stack; no network, Docker or real provider key is used.
# The subshells below change the provider variables on purpose, so the parent keeps its own.
# shellcheck disable=SC2030,SC2031
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIB="$ROOT_DIR/scripts/lib/provider-test-double.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

FAILURES=0
pass() { printf 'ok   %s\n' "$1"; }
fail() { printf 'FAIL %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
assert() { local name="$1"; shift; if "$@"; then pass "$name"; else fail "$name"; fi; }
refute() { local name="$1"; shift; if "$@"; then fail "$name"; else pass "$name"; fi; }

# shellcheck source=../lib/provider-test-double.sh
. "$LIB"
# shellcheck source=../lib/secret-match.sh
. "$ROOT_DIR/scripts/lib/secret-match.sh"

# A value shaped like a real key, generated for this run only. It must never reach an override, an export, an
# argument list or any output.
REAL_LOOKING_KEY="sk-proj-$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n')"

# --- only loopback destinations are test doubles
assert "the loopback stub is a test destination" provider_test_url_ok "http://127.0.0.1:18080/v1"
assert "a refused loopback port is a test destination" provider_test_url_ok "http://127.0.0.1:9/v1"
assert "localhost over https is a test destination" provider_test_url_ok "https://localhost:8443/v1"
assert "IPv6 loopback is a test destination" provider_test_url_ok "http://[::1]:18080/v1"
refute "the real OpenAI endpoint is not a test destination" provider_test_url_ok "https://api.openai.com/v1"
refute "a blackholed private address is not a test destination" provider_test_url_ok "http://10.255.255.1:81/v1"
refute "a host that starts with localhost is not a test destination" provider_test_url_ok "http://localhost.example.com/v1"
refute "userinfo cannot smuggle a host" provider_test_url_ok "http://127.0.0.1@example.com/v1"
refute "a port cannot carry a host name" provider_test_url_ok "http://127.0.0.1:18080.example.com/v1"
refute "a query is not a test destination" provider_test_url_ok "http://127.0.0.1:18080/v1?x=1"
refute "a newline cannot smuggle a second URL" provider_test_url_ok $'http://127.0.0.1:18080/v1\nhttps://api.openai.com/v1'
refute "an empty value is not a test destination" provider_test_url_ok ""

# --- the override always carries the synthetic key
OV="$TMP/override.yaml"
DOKENE_AI_OPENAI_API_KEY="$REAL_LOOKING_KEY" provider_test_override "$OV" "http://127.0.0.1:18080/v1" \
    '      DOKENE_AI_RETRY_MAX_ATTEMPTS: "3"'
assert "the override sets the synthetic key" grep -qxF "      DOKENE_AI_OPENAI_API_KEY: \"$PROVIDER_TEST_API_KEY\"" "$OV"
assert "the override sets the loopback base URL" grep -qxF '      DOKENE_AI_OPENAI_BASE_URL: "http://127.0.0.1:18080/v1"' "$OV"
assert "the override keeps the extra environment" grep -qxF '      DOKENE_AI_RETRY_MAX_ATTEMPTS: "3"' "$OV"
assert "the override sets the key exactly once" test "$(grep -c DOKENE_AI_OPENAI_API_KEY "$OV")" = 1
refute "the caller's key never reaches the override" contains_literal "$OV" "$REAL_LOOKING_KEY"

printf 'previous\n' > "$OV"
provider_test_override "$OV" "http://10.255.255.1:81/v1" '' 2> "$TMP/err"
assert "a non-loopback destination is refused with status 2" test "$?" = 2
assert "a refused destination leaves the previous override untouched" grep -qx previous "$OV"
provider_test_override "$OV" "http://127.0.0.1:18080/v1" "      DOKENE_AI_OPENAI_API_KEY: \"x\"" 2>> "$TMP/err"
assert "extra environment cannot replace the key (status 2)" test "$?" = 2
provider_test_override "$OV" "http://127.0.0.1:18080/v1" '      DOKENE_AI_OPENAI_BASE_URL: "https://api.openai.com/v1"' 2>> "$TMP/err"
assert "extra environment cannot replace the base URL (status 2)" test "$?" = 2
assert "the override file was not rewritten by a refused call" grep -qx previous "$OV"
for alias in DOKENE_AI_OPENAI_APIKEY DOKENE_AI_OPENAIAPI_KEY DOKENEAIOPENAIAPIKEY DOKENE_AI_OPENAI_BASEURL \
    DOKENE_AI_OPENAI_ADDITIONAL_TRUSTED_HOSTS DOKENE_AI_PROVIDER_ DOKENE__AI__PROVIDER SPRING_APPLICATION_JSON \
    SPRING_CONFIG_IMPORT SPRING_CONFIG_ADDITIONAL_LOCATION JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS \
    LD_PRELOAD LD_LIBRARY_PATH LOADER_PATH CLASSPATH BASH_ENV ENV PATH JAVA_HOME; do
    provider_test_override "$OV" "http://127.0.0.1:18080/v1" "      $alias: \"x\"" 2>> "$TMP/err"
    assert "extra environment cannot set $alias (status 2)" test "$?" = 2
done
# The `$` values below are literal on purpose: Compose substitution must be refused, not expanded here.
# shellcheck disable=SC2016
for bad in '      dokene_ai_openai_api_key: "x"' '    env_file: other.env' '      DOKENE_AI_RETRY_MAX_ATTEMPTS: 3' \
    '      DOKENE_AI_RETRY_MAX_ATTEMPTS: "3" # x' '      DOKENE_AI_RETRY_MAX_ATTEMPTS: "\"x"' \
    '      DOKENE_AI_OPENAI_MODEL: "${DOKENE_AI_OPENAI_API_KEY}"' '      DOKENE_AI_OPENAI_MODEL: "$DOKENE_AI_OPENAI_API_KEY"' \
    '      DOKENE_AI_RETRY_MAX_ATTEMPTS: "$$3"'; do
    provider_test_override "$OV" "http://127.0.0.1:18080/v1" "$bad" 2>> "$TMP/err"
    assert "extra environment that is not one NAME: \"value\" line is refused: $bad" test "$?" = 2
done
assert "no refused alias rewrote the override file" grep -qx previous "$OV"
# Settings that cannot change the destination or the key stay allowed, whatever words their names share.
for harmless in DOKENE_AI_OPENAI_TIMEOUT DOKENE_AI_OPENAI_MAX_OUTPUT_TOKENS DOKENE_INTEGRATION_WHATSAPP_GRAPH_BASE_URL; do
    provider_test_override "$OV" "http://127.0.0.1:18080/v1" "      $harmless: \"3\"" 2>> "$TMP/err"
    assert "extra environment may set $harmless" grep -qxF "      $harmless: \"3\"" "$OV"
done
printf 'previous\n' > "$OV"
provider_test_override "" "http://127.0.0.1:18080/v1" '' 2>> "$TMP/err"
assert "a missing override file is refused with status 2" test "$?" = 2

# --- export and unexport for dev-env.sh restarts
(
    export DOKENE_AI_OPENAI_API_KEY="$REAL_LOOKING_KEY"
    provider_test_export "http://127.0.0.1:18080/v1" || exit 10
    [ "$DOKENE_AI_OPENAI_API_KEY" = "$PROVIDER_TEST_API_KEY" ] || exit 11
    [ "$DOKENE_AI_OPENAI_BASE_URL" = "http://127.0.0.1:18080/v1" ] || exit 12
    provider_test_unexport
    [ -z "${DOKENE_AI_OPENAI_API_KEY+set}" ] || exit 13
    [ -z "$DOKENE_AI_OPENAI_BASE_URL" ] || exit 14
)
assert "export replaces the key with the synthetic one; unexport clears both" test "$?" = 0
(
    export DOKENE_AI_OPENAI_API_KEY="$REAL_LOOKING_KEY" DOKENE_AI_OPENAI_BASE_URL=
    provider_test_export "https://api.openai.com/v1" 2>/dev/null
    rc=$?
    [ "$rc" = 2 ] && [ "$DOKENE_AI_OPENAI_API_KEY" = "$REAL_LOOKING_KEY" ] && [ -z "$DOKENE_AI_OPENAI_BASE_URL" ]
)
assert "a refused export changes nothing" test "$?" = 0

# --- the running backend is compared with its baseline, and stopped when it could send a real key
# The baseline is the repository's own compose.yaml, so a drift between the library and the compose file shows here.
export PROVIDER_TEST_COMPOSE_FILE="$ROOT_DIR/compose.yaml" PROVIDER_TEST_DOCKERFILE="$ROOT_DIR/infra/docker/backend/Dockerfile"
assert "the Dockerfile baseline is the backend image's final stage" test "$(_provider_test_dockerfile_baseline "$PROVIDER_TEST_DOCKERFILE")" \
    = "$(printf '%s\n' 'ENTRYPOINT ["/app/entrypoint.sh"]' 'CMD null' 'WORKDIR "/app"' \
        'ENV JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError')"
COMPOSE_NAMES="$(_provider_test_compose_names "$PROVIDER_TEST_COMPOSE_FILE")"
assert "the compose file's backend environment is read" test "$(printf '%s\n' "$COMPOSE_NAMES" | grep -c .)" -gt 30
assert "the compose baseline includes the provider key" grep -qx DOKENE_AI_OPENAI_API_KEY <<< "$COMPOSE_NAMES"
assert "the compose baseline's only mount is the signing key" \
    test "$(_provider_test_compose_mounts "$PROVIDER_TEST_COMPOSE_FILE")" = /run/secrets/dokene/DOKENE_TENANT_CONTEXT_SIGNING_KEY

BIN="$TMP/bin"; mkdir -p "$BIN"
cat > "$BIN/docker" <<'STUB'
#!/usr/bin/env bash
# Stands in for docker: the container's and the image's configuration come from files in $DOCKER_STATE. Environment
# entries are NUL-separated, so a value may hold a newline exactly as docker would print it.
printf '%s\n' "$*" >> "$DOCKER_LOG"
st="$DOCKER_STATE"
env_lines() { local e; while IFS= read -r -d '' e; do printf '%s %s\n' "$1" "$e"; done < "$2"; }
case "$1 $2" in
    "compose ps") [ -s "$st/env" ] && echo backend-container-id ;;
    "compose stop") : > "$st/env"; echo stopped >> "$st/events" ;;
    "inspect --format")
        if [ "$3" = '{{.Image}}' ]; then echo sha256:backend-image; exit 0; fi
        printf 'ENTRYPOINT %s\nCMD %s\nWORKDIR %s\n' "$(cat "$st/entrypoint")" "$(cat "$st/cmd")" "$(cat "$st/workdir")"
        env_lines ENV "$st/env"
        [ -f "$st/mounts" ] && sed 's/^/MOUNT /' "$st/mounts" ;;
    "image inspect")
        printf 'ENTRYPOINT %s\nCMD %s\nWORKDIR %s\n' "$(cat "$st/image_entrypoint")" "$(cat "$st/image_cmd")" \
            "$(cat "$st/image_workdir")"
        env_lines ENV "$st/image_env" ;;
esac
exit 0
STUB
chmod +x "$BIN/docker"
export DOCKER_LOG="$TMP/docker.log" DOCKER_STATE="$TMP/state"
mkdir -p "$DOCKER_STATE"
IMAGE_ENV=('PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' 'JAVA_HOME=/opt/java/openjdk'
    'LANG=en_US.UTF-8' 'LANGUAGE=en_US:en' 'LC_ALL=en_US.UTF-8' 'JAVA_VERSION=jdk-26.0.2+8'
    'JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError')
printf '%s\0' "${IMAGE_ENV[@]}" > "$DOCKER_STATE/image_env"

# backend_env URL KEY [ENTRY...]: a backend like the real one. Every compose name holds a placeholder, the image's
# variables keep the image's values, the key and base URL are the arguments, and each ENTRY (NAME=value) replaces or
# adds that variable. The entrypoint, command, working directory and mounts are the baseline's.
backend_env() {
    local url="$1" key="$2" name entry skip extra
    shift 2
    : > "$DOCKER_STATE/env"
    while IFS= read -r name; do
        case "$name" in
            DOKENE_AI_OPENAI_API_KEY) entry="$name=$key" ;;
            DOKENE_AI_OPENAI_BASE_URL) entry="$name=$url" ;;
            *) entry="$name=placeholder" ;;
        esac
        skip=''
        for extra in "$@"; do [ "${extra%%=*}" = "$name" ] && skip=1; done
        [ -n "$skip" ] || printf '%s\0' "$entry" >> "$DOCKER_STATE/env"
    done <<< "$COMPOSE_NAMES"
    for entry in "${IMAGE_ENV[@]}"; do
        skip=''
        for extra in "$@"; do [ "${extra%%=*}" = "${entry%%=*}" ] && skip=1; done
        [ -n "$skip" ] || printf '%s\0' "$entry" >> "$DOCKER_STATE/env"
    done
    for extra in "$@"; do printf '%s\0' "$extra" >> "$DOCKER_STATE/env"; done
    printf '["/app/entrypoint.sh"]' > "$DOCKER_STATE/entrypoint"
    printf 'null' > "$DOCKER_STATE/cmd"
    printf '"/app"' > "$DOCKER_STATE/workdir"
    printf '/run/secrets/dokene/DOKENE_TENANT_CONTEXT_SIGNING_KEY\n' > "$DOCKER_STATE/mounts"
    printf '["/app/entrypoint.sh"]' > "$DOCKER_STATE/image_entrypoint"
    printf 'null' > "$DOCKER_STATE/image_cmd"
    printf '"/app"' > "$DOCKER_STATE/image_workdir"
    printf '%s\0' "${IMAGE_ENV[@]}" > "$DOCKER_STATE/image_env"
    : > "$DOCKER_STATE/events"
}
check_backend() { PATH="$BIN:$PATH" provider_test_assert_backend 2> "$TMP/assert.err"; }
stopped() { grep -qx stopped "$DOCKER_STATE/events"; }
ok_url="http://127.0.0.1:18080/v1"
expect_backend() { # NAME RC: runs the check on the prepared backend and expects RC
    local rc
    check_backend; rc=$?
    assert "$1" test "$rc" = "$2"
    cat "$TMP/assert.err" >> "$TMP/err"
}

backend_env "$ok_url" "$PROVIDER_TEST_API_KEY"
expect_backend "a backend like the real one, on a loopback double with the synthetic key, passes" 0
refute "a passing backend is not stopped" stopped

backend_env "$ok_url" "$REAL_LOOKING_KEY"
expect_backend "a real-looking key on the test double fails closed with status 3" 3
assert "the backend holding that key is stopped" stopped
assert "the failure names the key variable" grep -qF DOKENE_AI_OPENAI_API_KEY "$TMP/assert.err"
backend_env "" "$REAL_LOOKING_KEY"
expect_backend "the default (real) OpenAI endpoint fails closed" 3
assert "that backend is stopped" stopped
backend_env "http://10.255.255.1:81/v1" "$PROVIDER_TEST_API_KEY"
expect_backend "a non-loopback base URL fails closed even with the synthetic key" 3
backend_env "$ok_url" ""
expect_backend "a blank key fails closed" 3

# Anything the compose file and the image do not define fails closed, whatever it would do: other spellings of the
# key or base URL, configuration sources, JVM option variables, the dynamic linker, launchers and shells.
# The `$` forms are literal script text on purpose.
# shellcheck disable=SC2016
for extra in "DOKENE_AI_OPENAI_APIKEY=$REAL_LOOKING_KEY" "dokene_ai_openai_api_key=$REAL_LOOKING_KEY" \
    "DOKENE_AI_OPENAI_API__KEY=$REAL_LOOKING_KEY" "DOKENE_AI_OPENAI_APIKEY=$PROVIDER_TEST_API_KEY" \
    'DOKENE_AI_OPENAI_BASEURL=https://api.openai.com/v1' \
    "SPRING_APPLICATION_JSON={\"dokene\":{\"ai\":{\"openai\":{\"api-key\":\"$REAL_LOOKING_KEY\"}}}}" \
    'SPRING_CONFIG_ADDITIONAL_LOCATION=file:/extra/' "JDK_JAVA_OPTIONS=-Ddokene.ai.openai.api-key=$REAL_LOOKING_KEY" \
    'JDK_JAVA_OPTIONS=-Xmx1g -Dfile.encoding=UTF-8' 'JDK_JAVA_OPTIONS=@/tmp/args' '_JAVA_OPTIONS=-XX:VMOptionsFile=/x' \
    'LD_PRELOAD=/tmp/libx.so' 'LD_PRELOAD=' 'LD_LIBRARY_PATH=/tmp' 'LD_AUDIT=/tmp/libx.so' 'LOADER_PATH=/tmp/lib' \
    'CLASSPATH=/tmp/x.jar' 'BASH_ENV=/tmp/rc' 'MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=*'; do
    backend_env "$ok_url" "$PROVIDER_TEST_API_KEY" "$extra"
    expect_backend "a variable outside the baseline fails closed: ${extra%%=*}" 3
done
# The image's own variables must keep the image's values.
for extra in 'PATH=/tmp/bin:/usr/bin' 'JAVA_HOME=/tmp/jre' 'LANG=C.UTF-8' \
    'JAVA_TOOL_OPTIONS=-javaagent:/tmp/agent.jar' 'JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket' \
    'JAVA_TOOL_OPTIONS=-Xrunjdwp:transport=dt_socket' 'JAVA_TOOL_OPTIONS=-XX:OnError=/tmp/x' \
    'JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:Flags=/x' 'JAVA_TOOL_OPTIONS=-Dloader.path=/tmp/lib'; do
    backend_env "$ok_url" "$PROVIDER_TEST_API_KEY" "$extra"
    expect_backend "an image variable with another value fails closed: $extra" 3
done
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY" "${IMAGE_ENV[@]}"
expect_backend "the image's own variables with the image's values pass" 0
# Names a script's own override adds are declared, and must pass the same reserved-name rules as override extras.
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY" 'MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=metrics' \
    'MANAGEMENT_ENDPOINT_METRICS_ACCESS=read-only'
PROVIDER_TEST_EXTRA_NAMES='MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE MANAGEMENT_ENDPOINT_METRICS_ACCESS' check_backend; rc=$?
assert "declared extra names (res10's metrics exposure) pass" test "$rc" = 0
for declared in LD_PRELOAD CLASSPATH BASH_ENV PATH DOKENE_AI_OPENAI_APIKEY SPRING_APPLICATION_JSON JDK_JAVA_OPTIONS 'A-B'; do
    backend_env "$ok_url" "$PROVIDER_TEST_API_KEY"
    PROVIDER_TEST_EXTRA_NAMES="$declared" check_backend; rc=$?
    assert "a reserved or malformed declared name fails closed: $declared" test "$rc" = 3
done
# Mounts: only the compose file's secret targets.
for mount in /run/secrets/dokene/DOKENE_AI_OPENAI_API_KEY /run/secrets/dokene /run/secrets /run \
    /run/secrets/dokene/OTHER_FILE /run/secrets/application.yml /app/config /app/config/application.yml \
    /app/application.properties /app /config /app/dokene.jar /app/entrypoint.sh /opt/java/openjdk /opt /etc \
    /etc/ld.so.preload /usr/local/bin/java /bin /lib64 /var/lib/dokene /app-data /opt/config.txt /tmp/x; do
    backend_env "$ok_url" "$PROVIDER_TEST_API_KEY"
    printf '/run/secrets/dokene/DOKENE_TENANT_CONTEXT_SIGNING_KEY\n%s\n' "$mount" > "$DOCKER_STATE/mounts"
    expect_backend "a mount outside the baseline fails closed: $mount" 3
done
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY"
: > "$DOCKER_STATE/mounts"
expect_backend "a backend without the signing-key mount still passes the provider check" 0
# Entrypoint, command and working directory must be the image's.
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY"
printf '["java","-Ddokene.ai.openai.api-key=x","-jar","/app/dokene.jar"]' > "$DOCKER_STATE/entrypoint"
expect_backend "a changed entrypoint fails closed" 3
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY"
printf '["--dokene.ai.openai.base-url=https://example.com/v1"]' > "$DOCKER_STATE/cmd"
expect_backend "a command added to the image's fails closed" 3
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY"
printf '"/tmp"' > "$DOCKER_STATE/workdir"
expect_backend "a changed working directory fails closed" 3
# A value spanning several lines hides its continuation from any rule, so it fails closed.
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY" $'DOKENE_AI_OPENAI_MODEL=gpt-test\n-javaagent:/tmp/agent.jar'
expect_backend "a multi-line value fails closed" 3
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY" $'DOKENE_AI_OPENAI_MODEL=gpt-test\nENV LD_PRELOAD=/tmp/libx.so'
expect_backend "a multi-line value that forges another entry fails closed" 3
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY" $'JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75\n-javaagent:/tmp/a.jar'
expect_backend "a multi-line JVM option fails closed" 3
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY" 'NO_SEPARATOR'
expect_backend "an environment entry without '=' fails closed" 3
# The baseline itself must be readable.
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY"
PROVIDER_TEST_COMPOSE_FILE="$TMP/missing.yaml" check_backend; rc=$?
assert "a missing compose file fails closed" test "$rc" = 3
printf 'services:\n  other:\n    image: x\n' > "$TMP/no-backend.yaml"
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY"
PROVIDER_TEST_COMPOSE_FILE="$TMP/no-backend.yaml" check_backend; rc=$?
assert "a compose file without a backend environment fails closed" test "$rc" = 3
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY"
: > "$DOCKER_STATE/image_env"
expect_backend "a backend whose image cannot be read fails closed" 3
printf '%s\0' "${IMAGE_ENV[@]}" > "$DOCKER_STATE/image_env"
# The image itself must match this tree's Dockerfile and base image: a modified image cannot bring settings along,
# even when the container keeps the image's values.
for baked in "DOKENE_AI_OPENAI_APIKEY=$REAL_LOOKING_KEY" "SPRING_APPLICATION_JSON={\"dokene\":{}}" 'BASH_ENV=/tmp/rc' \
    'LD_PRELOAD=/tmp/libx.so' 'JDK_JAVA_OPTIONS=-javaagent:/tmp/a.jar'; do
    backend_env "$ok_url" "$PROVIDER_TEST_API_KEY" "$baked"
    printf '%s\0' "${IMAGE_ENV[@]}" "$baked" > "$DOCKER_STATE/image_env"
    expect_backend "a variable baked into the image fails closed: ${baked%%=*}" 3
done
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY" 'JAVA_TOOL_OPTIONS=-javaagent:/tmp/a.jar'
{ printf '%s\0' "${IMAGE_ENV[@]:0:6}"; printf '%s\0' 'JAVA_TOOL_OPTIONS=-javaagent:/tmp/a.jar'; } > "$DOCKER_STATE/image_env"
expect_backend "an image whose JAVA_TOOL_OPTIONS differs from its Dockerfile fails closed" 3
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY"
printf '["/tmp/run.sh"]' > "$DOCKER_STATE/image_entrypoint"; printf '["/tmp/run.sh"]' > "$DOCKER_STATE/entrypoint"
expect_backend "an image whose entrypoint differs from its Dockerfile fails closed" 3
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY"
printf '"/tmp"' > "$DOCKER_STATE/image_workdir"; printf '"/tmp"' > "$DOCKER_STATE/workdir"
expect_backend "an image whose working directory differs from its Dockerfile fails closed" 3
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY"
printf '["--x"]' > "$DOCKER_STATE/image_cmd"; printf '["--x"]' > "$DOCKER_STATE/cmd"
expect_backend "an image with a command its Dockerfile does not set fails closed" 3
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY"
PROVIDER_TEST_DOCKERFILE="$TMP/missing.Dockerfile" check_backend; rc=$?
assert "a missing Dockerfile fails closed" test "$rc" = 3
printf '%s\n' 'FROM scratch' 'ENTRYPOINT /app/entrypoint.sh' 'WORKDIR /app' > "$TMP/shell-form.Dockerfile"
backend_env "$ok_url" "$PROVIDER_TEST_API_KEY"
PROVIDER_TEST_DOCKERFILE="$TMP/shell-form.Dockerfile" check_backend; rc=$?
assert "a Dockerfile form the check cannot read fails closed" test "$rc" = 3

: > "$DOCKER_STATE/env"
expect_backend "no running backend fails closed" 3

refute "no docker argument list held the key" contains_literal "$DOCKER_LOG" "$REAL_LOOKING_KEY"
refute "no message printed the key" contains_literal "$TMP/err" "$REAL_LOOKING_KEY"

# --- every provider failure script and stub-phase driver goes through the library
for script in \
    docs/verification/issue-123-evidence/scripts/res0234.sh \
    docs/verification/issue-123-evidence/scripts/res04b.sh \
    docs/verification/issue-123-evidence/scripts/res05.sh \
    docs/verification/issue-123-evidence/scripts/res06.sh \
    docs/verification/issue-123-evidence/scripts/res07.mjs \
    docs/verification/issue-123-evidence/scripts/res10.sh \
    docs/verification/phase-3-evidence/scripts/regress.sh \
    docs/verification/phase-3-evidence/scripts/rerun-ui.sh \
    docs/verification/issue-118-evidence/scripts/drive-ui.sh \
    docs/verification/issue-118-evidence/scripts/drive-security-stub-phase.sh; do
    assert "$script checks the backend against the test double" grep -q provider_test_assert_backend "$ROOT_DIR/$script"
done
# No script may point the backend at a test double by hand any more: that is how the configured key went along.
# A line that sets a synthetic key next to the base URL (the evaluation harness of issues 246-253) is allowed.
# stub_urls_clean DIR: 0 when no evidence script or config under DIR mentions the provider base URL setting outside a
# reviewed line, 1 when one does (the lines go to stdout), 2 when DIR could not be scanned: missing, holding no such
# file, or grep reported an error. Every mention counts, in any spelling Spring binds (DOKENE_AI_OPENAI_BASE_URL,
# DOKENE_AI_OPENAI_BASEURL, dokene.ai.openai.base-url, baseUrl) and in any form (assignment, setter call, -D option,
# object key), so no assignment syntax can slip past. Exempt are reads in the reviewed files that carry the
# REVIEWED_READ marker, and the exact line of the issue 246-253 evaluation harness, which sets a synthetic key next
# to it. Recorded data (.json) is not scanned; in a recorded diff (.patch) only added lines count. Files are scanned
# one at a time, so a ':' in a path cannot be mistaken for the end of the file name.
# Names are matched case-insensitively. Recorded output (.txt, .json) is not scanned: it records past runs and is
# never run.
STUB_URL_SCAN_TYPES=(-iname '*.sh' -o -iname '*.bash' -o -iname '*.mjs' -o -iname '*.js' -o -iname '*.ts'
    -o -iname '*.py' -o -iname '*.java' -o -iname '*.kts' -o -iname '*.yaml' -o -iname '*.yml' -o -iname '*.conf'
    -o -iname '*.properties' -o -iname '*.env' -o -iname '.env*' -o -iname 'Dockerfile*' -o -iname '*.patch')
# Any run of separators between the words, as relaxed binding ignores them (DOKENE_AI__OPENAI_BASE_URL too).
STUB_URL_PATTERN='dokene[._-]*ai[._-]*openai[._-]*base[._-]*url'
# A nested YAML key (dokene: / ai: / openai: / base-url:) in a file that mentions openai.
STUB_URL_YAML_KEY='^[[:space:]]*base[._-]*url[[:space:]]*:'
STUB_URL_ALLOWED_FILE='/issue-246-253-evidence/scripts/run-eval-against-stub.sh'
STUB_URL_ALLOWED_LINE='export DOKENE_AI_OPENAI_API_KEY=sk-qa-stub-not-a-real-key DOKENE_AI_OPENAI_BASE_URL=http://127.0.0.1:18080/v1'
REVIEWED_READ='# provider-test-double: reviewed read'
STUB_URL_REVIEWED_FILES=('/issue-121-evidence/scripts/sec121-d.sh' '/issue-120-evidence/scripts/ai120-c.sh')
stub_url_exempt() { # FILE CONTENT: true when this mention is a reviewed read or the exact harness line
    local reviewed
    case "$1" in *"$STUB_URL_ALLOWED_FILE") [ "$2" = "$STUB_URL_ALLOWED_LINE" ] && return 0 ;; esac
    for reviewed in "${STUB_URL_REVIEWED_FILES[@]}"; do
        case "$1" in *"$reviewed") case "$2" in *"$REVIEWED_READ") return 0 ;; esac ;; esac
    done
    return 1
}
stub_urls_clean() {
    local dir="$1" file hits hit content scanned=0 found=0 rc
    [ -d "$dir" ] || return 2
    : > "$TMP/scan.err"
    while IFS= read -r -d '' file; do
        scanned=1
        case "$file" in
            *.[pP][aA][tT][cC][hH])
                # Only real added lines of a recorded diff count; '+++ ' is a file header only right after '--- '.
                hits="$(awk 'prev ~ /^--- / && /^\+\+\+ / { prev = $0; next } { prev = $0 } /^\+/ { print NR ":" $0 }' \
                    "$file" 2>> "$TMP/scan.err" | grep -iE "$STUB_URL_PATTERN" 2>> "$TMP/scan.err")"; rc=$? ;;
            *.[yY][aA][mM][lL] | *.[yY][mM][lL])
                if grep -qi openai "$file" 2>> "$TMP/scan.err"; then
                    hits="$(grep -niE -e "$STUB_URL_PATTERN" -e "$STUB_URL_YAML_KEY" "$file" 2>> "$TMP/scan.err")"; rc=$?
                else
                    hits="$(grep -niE "$STUB_URL_PATTERN" "$file" 2>> "$TMP/scan.err")"; rc=$?
                fi ;;
            *) hits="$(grep -niE "$STUB_URL_PATTERN" "$file" 2>> "$TMP/scan.err")"; rc=$? ;;
        esac
        [ "$rc" -le 1 ] || return 2
        [ -n "$hits" ] || continue
        while IFS= read -r hit; do
            content="${hit#*:}"
            stub_url_exempt "$file" "$content" && continue
            printf '%s:%s\n' "$file" "$hit"; found=1
        done <<< "$hits"
    done < <(find "$dir" -type f \( "${STUB_URL_SCAN_TYPES[@]}" \) -print0 2>> "$TMP/scan.err")
    [ "$scanned" = 1 ] && [ ! -s "$TMP/scan.err" ] || return 2
    [ "$found" = 0 ] || return 1
    return 0
}
guard_status() { stub_urls_clean "$1" > /dev/null; echo "$?"; }
SAMPLES="$TMP/samples"; mkdir -p "$SAMPLES"
i=0
SAMPLE_EXTS=(sh py mjs js yml ts properties java)  # each sample form lands in a different scanned file type
# The `$` forms below are literal on purpose: they are the script text the guard must catch. Every mention counts, so
# empty resets and comparisons outside the reviewed lines are caught too.
# shellcheck disable=SC2016
for form in 'export DOKENE_AI_OPENAI_BASE_URL="http://127.0.0.1:18080/v1"' "DOKENE_AI_OPENAI_BASE_URL='http://localhost:18080/v1'" \
    'DOKENE_AI_OPENAI_BASE_URL=http://[::1]:18080/v1' 'DOKENE_AI_OPENAI_BASE_URL=https://localhost:8443/v1' \
    '      DOKENE_AI_OPENAI_BASE_URL: "http://127.0.0.1:18080/v1"' 'env.DOKENE_AI_OPENAI_BASE_URL = "http://127.0.0.1:18080/v1"' \
    'DOKENE_AI_OPENAI_BASE_URL="$STUBURL"' "printf '      DOKENE_AI_OPENAI_BASE_URL: \"%s\"\\n' \"\$1\"" \
    'DOKENE_AI_OPENAI_BASE_URL=HTTP://LOCALHOST:1/v1' 'docker run -e DOKENE_AI_OPENAI_BASE_URL=x image' \
    "process.env['DOKENE_AI_OPENAI_BASE_URL'] = url;" 'os.environ["DOKENE_AI_OPENAI_BASE_URL"] = url' \
    '{ DOKENE_AI_OPENAI_BASE_URL: url }' \
    'DOKENE_AI_OPENAI_BASE_URL=http://127.0.0.1:18080/v1  # DOKENE_AI_OPENAI_API_KEY=sk-qa-stub-not-a-real-key' \
    'pb.environment().put("DOKENE_AI_OPENAI_BASE_URL", url);' 'os.environ.setdefault("DOKENE_AI_OPENAI_BASE_URL", url)' \
    'os.putenv("DOKENE_AI_OPENAI_BASE_URL", url)' 'printf -v DOKENE_AI_OPENAI_BASE_URL %s "$url"' \
    'process.env.DOKENE_AI_OPENAI_BASE_URL ??= url;' 'DOKENE_AI_OPENAI_BASEURL=http://127.0.0.1:18080/v1' \
    'java -Ddokene.ai.openai.base-url=http://127.0.0.1:18080/v1 -jar app.jar' '{ "dokene.ai.openai.baseUrl": "x" }' \
    'export DOKENE_AI_OPENAI_BASE_URL=' 'if (process.env.DOKENE_AI_OPENAI_BASE_URL === "x") {}' \
    'DOKENE_AI__OPENAI_BASE_URL=http://127.0.0.1:18080/v1' 'dokene..ai--openai__base--url=x'; do
    i=$((i + 1)); mkdir -p "$SAMPLES/s$i"; printf '%s\n' "$form" > "$SAMPLES/s$i/script.${SAMPLE_EXTS[$((i % 8))]}"
    assert "the hand-set URL guard catches: $form" test "$(guard_status "$SAMPLES/s$i")" = 1
done
mkdir -p "$SAMPLES/ok"
printf '%s\n' 'echo "no provider setting here"' 'DOKENE_AI_OPENAI_MODEL=gpt-test' > "$SAMPLES/ok/script.sh"
assert "scripts that never mention the base URL pass" test "$(guard_status "$SAMPLES/ok")" = 0
mkdir -p "$SAMPLES/reviewed/issue-121-evidence/scripts"
# shellcheck disable=SC2016
printf '%s  %s\n' '[ -n "$DOKENE_AI_OPENAI_BASE_URL" ] && echo edited' "$REVIEWED_READ" \
    > "$SAMPLES/reviewed/issue-121-evidence/scripts/sec121-d.sh"
assert "an edited read with the marker in a reviewed file passes" test "$(guard_status "$SAMPLES/reviewed")" = 0
cp "$SAMPLES/reviewed/issue-121-evidence/scripts/sec121-d.sh" "$SAMPLES/reviewed/other.sh"
assert "the marker outside the reviewed files is caught" test "$(guard_status "$SAMPLES/reviewed")" = 1
rm "$SAMPLES/reviewed/other.sh"
# shellcheck disable=SC2016
printf '%s\n' '[ -n "$DOKENE_AI_OPENAI_BASE_URL" ] && echo edited' > "$SAMPLES/reviewed/issue-121-evidence/scripts/sec121-d.sh"
assert "a reviewed file's mention without the marker is caught" test "$(guard_status "$SAMPLES/reviewed")" = 1
mkdir -p "$SAMPLES/colon/run:2026-11-15/issue-120-evidence/scripts"
# shellcheck disable=SC2016
printf 'echo "$(env | grep ^DOKENE_AI_OPENAI_BASE_URL=)"  %s\n' "$REVIEWED_READ" \
    > "$SAMPLES/colon/run:2026-11-15/issue-120-evidence/scripts/ai120-c.sh"
assert "a reviewed read in a path holding ':' passes" test "$(guard_status "$SAMPLES/colon")" = 0
mkdir -p "$SAMPLES/patch"
# shellcheck disable=SC2016
printf '%s\n' '--- a/compose.yaml' '+++ b/compose.yaml' '       DOKENE_AI_OPENAI_BASE_URL: ${DOKENE_AI_OPENAI_BASE_URL:-}' \
    '-      DOKENE_AI_OPENAI_BASE_URL: old' > "$SAMPLES/patch/change.patch"
assert "context and removed lines of a recorded diff pass" test "$(guard_status "$SAMPLES/patch")" = 0
printf '%s\n' '+      DOKENE_AI_OPENAI_BASE_URL: "http://127.0.0.1:18080/v1"' >> "$SAMPLES/patch/change.patch"
assert "an added line of a recorded diff is caught" test "$(guard_status "$SAMPLES/patch")" = 1
mkdir -p "$SAMPLES/data"
printf '%s\n' '{ "DOKENE_AI_OPENAI_BASE_URL": "http://127.0.0.1:18080/v1" }' > "$SAMPLES/data/capture.json"
printf '%s\n' 'echo no setting' > "$SAMPLES/data/run.sh"
assert "recorded JSON data is not scanned" test "$(guard_status "$SAMPLES/data")" = 0
# Other file types, any case of extension, are scanned too.
for name in run.env .env.local build.gradle.kts Dockerfile Dockerfile.qa RUN.SH; do
    mkdir -p "$SAMPLES/type-$name"
    printf '%s\n' 'DOKENE_AI_OPENAI_BASE_URL=http://127.0.0.1:18080/v1' > "$SAMPLES/type-$name/$name"
    assert "a mention in $name is caught" test "$(guard_status "$SAMPLES/type-$name")" = 1
done
mkdir -p "$SAMPLES/yaml"
printf '%s\n' 'dokene:' '  ai:' '    openai:' '      base-url: http://127.0.0.1:18080/v1' > "$SAMPLES/yaml/application.yml"
assert "a nested YAML key in a file that mentions openai is caught" test "$(guard_status "$SAMPLES/yaml")" = 1
printf '%s\n' 'integration:' '  whatsapp:' '    graph:' '      base-url: http://127.0.0.1:18080' > "$SAMPLES/yaml/application.yml"
assert "a nested base-url of another integration passes" test "$(guard_status "$SAMPLES/yaml")" = 0
mkdir -p "$SAMPLES/patch2"
# An added line whose content starts with '++ ' looks like a '+++ ' file header; only one right after '--- ' is one.
printf '%s\n' '--- a/x.sh' '+++ b/x.sh' '@@ -1 +1 @@' '+++ DOKENE_AI_OPENAI_BASE_URL=http://127.0.0.1:18080/v1' > "$SAMPLES/patch2/c.patch"
assert "an added line of a recorded diff that starts with '++' is caught" test "$(guard_status "$SAMPLES/patch2")" = 1
printf '%s\n' '--- a/DOKENE_AI_OPENAI_BASE_URL.sh' '+++ b/DOKENE_AI_OPENAI_BASE_URL.sh' '@@ -1 +1 @@' ' echo' > "$SAMPLES/patch2/c.patch"
assert "the file headers of a recorded diff pass" test "$(guard_status "$SAMPLES/patch2")" = 0
assert "a missing directory cannot pass the guard" test "$(guard_status "$SAMPLES/missing")" = 2
mkdir -p "$SAMPLES/empty"
assert "a directory with no script cannot pass the guard" test "$(guard_status "$SAMPLES/empty")" = 2
mkdir -p "$SAMPLES/allowed$STUB_URL_ALLOWED_FILE" && rmdir "$SAMPLES/allowed$STUB_URL_ALLOWED_FILE"
printf '%s\n' "$STUB_URL_ALLOWED_LINE" > "$SAMPLES/allowed$STUB_URL_ALLOWED_FILE"
assert "the exact allowed harness line passes" test "$(guard_status "$SAMPLES/allowed")" = 0
printf '%s\n' "$STUB_URL_ALLOWED_LINE" > "$SAMPLES/allowed/elsewhere.sh"
assert "the allowed line in another file is caught" test "$(guard_status "$SAMPLES/allowed")" = 1
assert "no evidence script sets a provider base URL without the library" test "$(guard_status "$ROOT_DIR/docs/verification")" = 0
assert "the stub accepts only the synthetic key" grep -qF "\"Bearer $PROVIDER_TEST_API_KEY\"" "$ROOT_DIR/docs/verification/issue-121-evidence/scripts/Stub.java"
refute "no setting can change the key the stub accepts" grep -q STUB_EXPECTED_API_KEY "$ROOT_DIR/docs/verification/issue-121-evidence/scripts/Stub.java"

if [ "$FAILURES" -gt 0 ]; then
    printf '%d check(s) failed\n' "$FAILURES"
    exit 1
fi
printf 'all provider test double checks passed\n'
