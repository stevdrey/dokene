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
    SPRING_CONFIG_IMPORT SPRING_CONFIG_ADDITIONAL_LOCATION JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS; do
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

# --- the running backend is checked, and stopped when it could send a real key
BIN="$TMP/bin"; mkdir -p "$BIN"
cat > "$BIN/docker" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$DOCKER_LOG"
case "$1 $2" in
    "compose ps") [ -s "$DOCKER_STATE/env" ] && echo backend-container-id ;;
    "compose stop") : > "$DOCKER_STATE/env"; echo stopped >> "$DOCKER_STATE/events" ;;
    "inspect --format") sed 's/^/ENV /' "$DOCKER_STATE/env"; [ -f "$DOCKER_STATE/mounts" ] && sed 's/^/MOUNT /' "$DOCKER_STATE/mounts" ;;
esac
exit 0
STUB
chmod +x "$BIN/docker"
export DOCKER_LOG="$TMP/docker.log" DOCKER_STATE="$TMP/state"
mkdir -p "$DOCKER_STATE"

# backend_env URL KEY [EXTRA_ENTRY...]: the backend's environment, with the image's own JVM options and a Spring
# security setting that mentions PROVIDER, both of which must pass.
backend_env() {
    local url="$1" key="$2"; shift 2
    {
        printf 'PATH=/usr/bin\nJAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError\n'
        printf 'SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_PROVIDER=dokene\nDOKENE_AI_PROVIDER=openai\n'
        printf 'DOKENE_AI_OPENAI_BASE_URL=%s\nDOKENE_AI_OPENAI_API_KEY=%s\n' "$url" "$key"
        [ "$#" -gt 0 ] && printf '%s\n' "$@"
    } > "$DOCKER_STATE/env"
    : > "$DOCKER_STATE/events"; rm -f "$DOCKER_STATE/mounts"
}
check_backend() { PATH="$BIN:$PATH" provider_test_assert_backend 2> "$TMP/assert.err"; }
stopped() { grep -qx stopped "$DOCKER_STATE/events"; }

backend_env "http://127.0.0.1:18080/v1" "$PROVIDER_TEST_API_KEY"
check_backend; rc=$?
assert "a loopback backend with the synthetic key passes" test "$rc" = 0
refute "a passing backend is not stopped" stopped

backend_env "http://127.0.0.1:18080/v1" "$REAL_LOOKING_KEY"
check_backend; rc=$?
assert "a real-looking key on the test double fails closed with status 3" test "$rc" = 3
assert "the backend holding that key is stopped" stopped
assert "the failure names the key variable" grep -qF DOKENE_AI_OPENAI_API_KEY "$TMP/assert.err"
cat "$TMP/assert.err" >> "$TMP/err"

backend_env "" "$REAL_LOOKING_KEY"
check_backend; rc=$?
assert "the default (real) OpenAI endpoint fails closed with status 3" test "$rc" = 3
assert "that backend is stopped" stopped
cat "$TMP/assert.err" >> "$TMP/err"

backend_env "http://10.255.255.1:81/v1" "$PROVIDER_TEST_API_KEY"
check_backend; rc=$?
assert "a non-loopback base URL fails closed even with the synthetic key" test "$rc" = 3
cat "$TMP/assert.err" >> "$TMP/err"

backend_env "http://127.0.0.1:18080/v1" ""
check_backend; rc=$?
assert "a blank key fails closed" test "$rc" = 3

# Spring binds other spellings of the same property; each must hold the test double's values.
for alias in DOKENE_AI_OPENAI_APIKEY dokene_ai_openai_api_key DOKENE_AI_OPENAI_API__KEY; do
    backend_env "http://127.0.0.1:18080/v1" "$PROVIDER_TEST_API_KEY" "$alias=$REAL_LOOKING_KEY"
    check_backend; rc=$?
    assert "a real-looking key under the alias $alias fails closed" test "$rc" = 3
    assert "the backend with the $alias alias is stopped" stopped
    cat "$TMP/assert.err" >> "$TMP/err"
done
backend_env "http://127.0.0.1:18080/v1" "$PROVIDER_TEST_API_KEY" "DOKENE_AI_OPENAI_APIKEY=$PROVIDER_TEST_API_KEY"
check_backend; rc=$?
assert "an alias that also holds the synthetic key passes" test "$rc" = 0
backend_env "http://127.0.0.1:18080/v1" "$PROVIDER_TEST_API_KEY" "DOKENE_AI_OPENAI_BASEURL=https://api.openai.com/v1"
check_backend; rc=$?
assert "a base URL alias on the real endpoint fails closed" test "$rc" = 3
backend_env "http://127.0.0.1:18080/v1" "$PROVIDER_TEST_API_KEY" \
    "SPRING_APPLICATION_JSON={\"dokene\":{\"ai\":{\"openai\":{\"api-key\":\"$REAL_LOOKING_KEY\"}}}}"
check_backend; rc=$?
assert "SPRING_APPLICATION_JSON on the backend fails closed" test "$rc" = 3
cat "$TMP/assert.err" >> "$TMP/err"
backend_env "http://127.0.0.1:18080/v1" "$PROVIDER_TEST_API_KEY" "SPRING_CONFIG_ADDITIONAL_LOCATION=file:/extra/"
check_backend; rc=$?
assert "SPRING_CONFIG_* on the backend fails closed" test "$rc" = 3
backend_env "http://127.0.0.1:18080/v1" "$PROVIDER_TEST_API_KEY" "JDK_JAVA_OPTIONS=-Ddokene.ai.openai.api-key=$REAL_LOOKING_KEY"
check_backend; rc=$?
assert "JVM options that set a provider property fail closed" test "$rc" = 3
cat "$TMP/assert.err" >> "$TMP/err"
backend_env "http://127.0.0.1:18080/v1" "$PROVIDER_TEST_API_KEY"
printf '/run/secrets/dokene/DOKENE_TENANT_CONTEXT_SIGNING_KEY\n/run/secrets/dokene/DOKENE_AI_OPENAI_API_KEY\n' > "$DOCKER_STATE/mounts"
check_backend; rc=$?
assert "a mounted config tree file for the key fails closed" test "$rc" = 3
backend_env "http://127.0.0.1:18080/v1" "$PROVIDER_TEST_API_KEY"
printf '/run/secrets/dokene/DOKENE_TENANT_CONTEXT_SIGNING_KEY\n' > "$DOCKER_STATE/mounts"
check_backend; rc=$?
assert "the backend's own secret mount passes" test "$rc" = 0
for mount in /run/secrets/dokene /run/secrets/dokene/ /run/secrets /run /run/secrets/dokene/OTHER_FILE \
    /run/secrets/application.yml /run/secrets/extra/application-local.properties; do
    backend_env "http://127.0.0.1:18080/v1" "$PROVIDER_TEST_API_KEY"
    printf '/run/secrets/dokene/DOKENE_TENANT_CONTEXT_SIGNING_KEY\n%s\n' "$mount" > "$DOCKER_STATE/mounts"
    check_backend; rc=$?
    assert "a mount at $mount fails closed" test "$rc" = 3
done
backend_env "http://127.0.0.1:18080/v1" "$PROVIDER_TEST_API_KEY"
printf '/var/lib/dokene\n/run/secrets/other/DOKENE_TENANT_CONTEXT_SIGNING_KEY\n' > "$DOCKER_STATE/mounts"
check_backend; rc=$?
assert "mounts outside the config tree with unreserved names pass" test "$rc" = 0
# Spring Boot also loads configuration from the image's working directory /app (./ and ./config/), and a mount
# there or over the JRE and system directories replaces code the backend runs.
for mount in /app/config /app/config/ /app/config/application.yml /app/config/extra/x.yml /app/application.properties \
    /app /config /config/application-local.yaml /opt/application.yml \
    /app/dokene.jar /app/entrypoint.sh /opt/java/openjdk /opt/java/openjdk/lib/security/java.security \
    /usr/local/bin/java /bin /sbin/x /lib/x.so /lib64; do
    backend_env "http://127.0.0.1:18080/v1" "$PROVIDER_TEST_API_KEY"
    printf '/run/secrets/dokene/DOKENE_TENANT_CONTEXT_SIGNING_KEY\n%s\n' "$mount" > "$DOCKER_STATE/mounts"
    check_backend; rc=$?
    assert "a mount at $mount fails closed" test "$rc" = 3
done
backend_env "http://127.0.0.1:18080/v1" "$PROVIDER_TEST_API_KEY"
printf '/run/secrets/dokene/DOKENE_TENANT_CONTEXT_SIGNING_KEY\n/var/lib/dokene\n/app-data\n/opt/config.txt\n' > "$DOCKER_STATE/mounts"
check_backend; rc=$?
assert "the signing key and mounts away from Spring's config locations pass" test "$rc" = 0
# A JVM argument file can hold -D properties the check cannot read.
for opts in 'JDK_JAVA_OPTIONS=@/tmp/args' 'JDK_JAVA_OPTIONS=-Xmx1g @/tmp/args' 'JAVA_TOOL_OPTIONS=-XX:VMOptionsFile=/x' \
    'JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:Flags=/x' '_JAVA_OPTIONS=-XX:VMOptionsFile=/x'; do
    backend_env "http://127.0.0.1:18080/v1" "$PROVIDER_TEST_API_KEY" "$opts"
    check_backend; rc=$?
    assert "JVM options with an argument file fail closed: $opts" test "$rc" = 3
done
backend_env "http://127.0.0.1:18080/v1" "$PROVIDER_TEST_API_KEY" 'JDK_JAVA_OPTIONS=-Xmx1g -Dfile.encoding=UTF-8'
check_backend; rc=$?
assert "ordinary JVM options next to the image's own pass" test "$rc" = 0

: > "$DOCKER_STATE/env"
check_backend; rc=$?
assert "no running backend fails closed" test "$rc" = 3
cat "$TMP/assert.err" >> "$TMP/err"

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
# stub_urls_clean DIR: 0 when no evidence script or config under DIR sets a non-empty provider base URL by hand (a
# literal URL in any spelling, a variable, a printf template, an object key, a bracket assignment such as
# process.env['…'] or os.environ["…"]), 1 when one does (the lines go to stdout), 2 when DIR could not be scanned:
# missing, holding no such file, or find or grep reported an error. An empty reset, reads and comparisons do not count.
# The only allowed hit is the exact line of the issue 246-253 evaluation harness, which sets a synthetic key with it.
STUB_URL_SCAN_TYPES=(-name '*.sh' -o -name '*.bash' -o -name '*.mjs' -o -name '*.js' -o -name '*.py' -o -name '*.java'
    -o -name '*.yaml' -o -name '*.yml' -o -name '*.conf' -o -name '*.patch')
STUB_URL_ALLOWED_FILE='/issue-246-253-evidence/scripts/run-eval-against-stub.sh'
STUB_URL_ALLOWED_LINE='export DOKENE_AI_OPENAI_API_KEY=sk-qa-stub-not-a-real-key DOKENE_AI_OPENAI_BASE_URL=http://127.0.0.1:18080/v1'
stub_urls_clean() {
    local dir="$1" count hit file rest content found=0
    [ -d "$dir" ] || return 2
    : > "$TMP/scan.err"
    count="$(find "$dir" -type f \( "${STUB_URL_SCAN_TYPES[@]}" \) -print 2>> "$TMP/scan.err" | wc -l)"
    [ "$count" -gt 0 ] && [ ! -s "$TMP/scan.err" ] || return 2
    # grep exits 1 when a file has no match, so find's status says nothing here; errors show up on stderr instead.
    find "$dir" -type f \( "${STUB_URL_SCAN_TYPES[@]}" \) -exec grep -HniE \
        "DOKENE_AI_OPENAI_BASE_URL[]\"']*[[:space:]]*[:=][[:space:]]*[\"']?[^[:space:]\"';)=]" {} + \
        > "$TMP/scan.hits" 2>> "$TMP/scan.err"
    [ ! -s "$TMP/scan.err" ] || return 2
    while IFS= read -r hit; do
        file="${hit%%:*}"; rest="${hit#*:}"; content="${rest#*:}"
        case "$file" in
            *"$STUB_URL_ALLOWED_FILE") [ "$content" = "$STUB_URL_ALLOWED_LINE" ] && continue ;;
        esac
        printf '%s\n' "$hit"; found=1
    done < "$TMP/scan.hits"
    [ "$found" = 0 ] || return 1
    return 0
}
guard_status() { stub_urls_clean "$1" > /dev/null; echo "$?"; }
SAMPLES="$TMP/samples"; mkdir -p "$SAMPLES"
i=0
SAMPLE_EXTS=(sh py mjs js yml)  # each sample form lands in a different scanned file type
# The `$` forms below are literal on purpose: they are the script text the guard must catch.
# shellcheck disable=SC2016
for form in 'export DOKENE_AI_OPENAI_BASE_URL="http://127.0.0.1:18080/v1"' "DOKENE_AI_OPENAI_BASE_URL='http://localhost:18080/v1'" \
    'DOKENE_AI_OPENAI_BASE_URL=http://[::1]:18080/v1' 'DOKENE_AI_OPENAI_BASE_URL=https://localhost:8443/v1' \
    '      DOKENE_AI_OPENAI_BASE_URL: "http://127.0.0.1:18080/v1"' 'env.DOKENE_AI_OPENAI_BASE_URL = "http://127.0.0.1:18080/v1"' \
    'DOKENE_AI_OPENAI_BASE_URL="$STUBURL"' "printf '      DOKENE_AI_OPENAI_BASE_URL: \"%s\"\\n' \"\$1\"" \
    'DOKENE_AI_OPENAI_BASE_URL=HTTP://LOCALHOST:1/v1' 'docker run -e DOKENE_AI_OPENAI_BASE_URL=x image' \
    "process.env['DOKENE_AI_OPENAI_BASE_URL'] = url;" 'os.environ["DOKENE_AI_OPENAI_BASE_URL"] = url' \
    '{ DOKENE_AI_OPENAI_BASE_URL: url }' \
    'DOKENE_AI_OPENAI_BASE_URL=http://127.0.0.1:18080/v1  # DOKENE_AI_OPENAI_API_KEY=sk-qa-stub-not-a-real-key'; do
    i=$((i + 1)); mkdir -p "$SAMPLES/s$i"; printf '%s\n' "$form" > "$SAMPLES/s$i/script.${SAMPLE_EXTS[$((i % 5))]}"
    assert "the hand-set URL guard catches: $form" test "$(guard_status "$SAMPLES/s$i")" = 1
done
mkdir -p "$SAMPLES/ok"
# shellcheck disable=SC2016
printf '%s\n' 'export DOKENE_AI_OPENAI_BASE_URL=' 'export DOKENE_AI_OPENAI_BASE_URL=;' \
    '[ -n "$DOKENE_AI_OPENAI_BASE_URL" ] && exit 2' "grep '^DOKENE_AI_OPENAI_BASE_URL=' env.txt" \
    "if (process.env.DOKENE_AI_OPENAI_BASE_URL === 'x') {}" "url = os.environ['DOKENE_AI_OPENAI_BASE_URL']" \
    > "$SAMPLES/ok/script.sh"
assert "an empty reset and reads of the base URL are not hand-set URLs" test "$(guard_status "$SAMPLES/ok")" = 0
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
