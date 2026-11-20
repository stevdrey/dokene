#!/usr/bin/env bash
# ABOUTME: Offline tests for scripts/verify-keycloak-bff-e2e.sh (issue 331): no failure message prints a secret, an
# ABOUTME: authorization code, OAuth state, a CSRF token or a session id, and the client secret checked is the effective one.
#
# The script and its libraries are copied into a throwaway tree with a synthetic .env, and run against a stub curl on
# PATH that plays Keycloak and the BFF. STUB_FAULT makes the stub misbehave at one point of the flow, so each failure
# path of the script runs with synthetic sensitive values in the responses it reports on. Every value is random per run;
# no network, Docker or real credential is used.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

FAILURES=0
pass() { printf 'ok   %s\n' "$1"; }
fail() { printf 'FAIL %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
assert() { local name="$1"; shift; if "$@"; then pass "$name"; else fail "$name"; fi; }
refute() { local name="$1"; shift; if "$@"; then fail "$name"; else pass "$name"; fi; }

synthetic() { printf 'syn%s%s' "$1" "$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"; }

# --- a throwaway copy of the script, so the repository's own .env is never read
REPO="$TMP/repo"
mkdir -p "$REPO/scripts/lib" "$TMP/bin" "$TMP/stub"
# E2E_SCRIPT_UNDER_TEST runs the same checks against another version of the script (a negative control).
cp "${E2E_SCRIPT_UNDER_TEST:-$ROOT_DIR/scripts/verify-keycloak-bff-e2e.sh}" "$REPO/scripts/verify-keycloak-bff-e2e.sh"
cp "$ROOT_DIR/scripts/lib/test-passwords.sh" "$ROOT_DIR/scripts/lib/secret-match.sh" \
   "$ROOT_DIR/scripts/lib/curl-protected.sh" "$REPO/scripts/lib/"

ENV_SECRET="$(synthetic envsecret)"
CALLER_SECRET="$(synthetic callersecret)"
OWNER_PW="$(synthetic ownerpw)"
OPERATOR_PW="$(synthetic operatorpw)"
VIEWER_PW="$(synthetic viewerpw)"
STATE="$(synthetic state)"
NONCE="$(synthetic nonce)"
CHALLENGE="$(synthetic challenge)"
CODE="$(synthetic code)"
CSRF="$(synthetic csrf)"
IDENTITY="$(synthetic identity)"
TOKEN="$(synthetic token)"
SESSION="$(synthetic session)"
HOST_TOKEN="$(synthetic hosttoken)$(synthetic more)"  # one 40-character label, the length of a real code or state

write_env() {  # write_env [SPRING_SECRET_LINE]: the synthetic .env of the throwaway tree
  {
    printf 'KEYCLOAK_PORT=8081\n'
    printf '%s\n' "${1-SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET=$ENV_SECRET}"
    printf 'DOKENE_TEST_OWNER_PASSWORD=%s\nDOKENE_TEST_OPERATOR_PASSWORD=%s\nDOKENE_TEST_VIEWER_PASSWORD=%s\n' \
      "$OWNER_PW" "$OPERATOR_PW" "$VIEWER_PW"
  } > "$REPO/.env"
}

# --- the stub stack
cat > "$TMP/bin/curl" <<'STUB'
#!/usr/bin/env bash
# Plays Keycloak (localhost:8081) and the BFF (localhost:8080) for verify-keycloak-bff-e2e.sh. Reads URLs, headers and
# cookies from the -K config curl-protected.sh writes, values given as NAME@- from stdin, and keeps the session state
# in $STUB_DIR. STUB_FAULT selects one misbehaviour. Records its argv in $STUB_DIR/argv.
KC=http://localhost:8081 BFF=http://localhost:8080
printf '%s\n' "$*" >> "$STUB_DIR/argv"
url="" out="" dump="" write="" jar_in="" jar_out="" method=GET cookie="" csrf="" stdin_field="" stdin_value=""
declare -A form=()
unquote() { local v="${1#*= \"}"; v="${v%\"}"; v="${v//\\\"/\"}"; printf '%s' "${v//\\\\/\\}"; }
while [ $# -gt 0 ]; do
  case "$1" in
    -K) while IFS= read -r line; do
          case "$line" in
            'url = '*) url="$(unquote "$line")" ;;
            'cookie = '*) cookie="$(unquote "$line")" ;;
            'header = '*) h="$(unquote "$line")"; case "$h" in X-CSRF-TOKEN:*) csrf="${h#X-CSRF-TOKEN: }" ;; esac ;;
          esac
        done < "$2"; shift ;;
    -o) out="$2"; shift ;;
    -D) dump="$2"; shift ;;
    -w) write="$2"; shift ;;
    -c) jar_out="$2"; shift ;;
    -b) jar_in="$2"; shift ;;
    -X) method="$2"; shift ;;
    --data-urlencode)
      method=POST
      case "$2" in
        *@-) stdin_field="${2%@-}"; stdin_value="$(cat)"; form[$stdin_field]="$stdin_value" ;;
        *=*) form[${2%%=*}]="${2#*=}" ;;
      esac
      shift ;;
    -*) ;;
    *) url="$1" ;;
  esac
  shift
done
if [ -z "$cookie" ] && [ -n "$jar_in" ] && [ -f "$jar_in" ]; then
  cookie="JSESSIONID=$(grep JSESSIONID "$jar_in" | awk '{print $NF}' | tail -n 1)"
fi
sid="${cookie#JSESSIONID=}"
fault() { [ "${STUB_FAULT:-}" = "$1" ]; }
active() { [ -n "$sid" ] && [ -f "$STUB_DIR/active" ] && [ "$(cat "$STUB_DIR/active")" = "$sid" ]; }

status=200 body="" headers=() new_sid=""
case "$url" in
  "$KC/realms/dokene/.well-known/openid-configuration") body='{}' ;;
  "$KC/realms/dokene/protocol/openid-connect/token")
    if fault token-error-reflects-secret; then
      status=400 body="{\"error\":\"${form[client_secret]:-}\"}"
    elif fault client-not-allowed; then
      status=400 body='{"error":"unauthorized_client","error_description":"Client not allowed for this grant"}'
    elif [ "${form[client_secret]:-}" = "$STUB_KC_SECRET" ]; then
      status=400 body='{"error":"invalid_grant","error_description":"Code not valid"}'
    else
      # Keycloak 26.8 answers a wrong client secret this way (checked against the real image for issue 331).
      status=401 body='{"error":"unauthorized_client","error_description":"Invalid client or Invalid client credentials"}'
    fi ;;
  "$BFF/api/session")
    if active; then
      body="{\"authenticated\":true,\"identityId\":\"$STUB_IDENTITY\",\"csrfToken\":\"$STUB_CSRF\"}"
      fault session-invalid && body="{\"authenticated\":false,\"identityId\":\"$STUB_IDENTITY\",\"csrfToken\":\"$STUB_CSRF\"}"
      fault session-authenticated-string && body="{\"authenticated\":\"true\",\"identityId\":\"$STUB_IDENTITY\",\"csrfToken\":\"$STUB_CSRF\"}"
      fault session-not-json && body="<html>$STUB_CSRF $STUB_IDENTITY</html>"
      fault session-trailing-garbage && body="{\"authenticated\":true,\"identityId\":\"$STUB_IDENTITY\",\"csrfToken\":\"$STUB_CSRF\"}<html>$STUB_CSRF</html>"
      fault session-two-objects && body="{\"authenticated\":true,\"identityId\":\"$STUB_IDENTITY\",\"csrfToken\":\"$STUB_CSRF\"}{}"
      fault session-body-secret && body="{\"authenticated\":true,\"identityId\":\"$STUB_IDENTITY\",\"csrfToken\":\"$STUB_CSRF\",\"debug\":\"$STUB_LEAK\"}"
      fault session-body-token && body="{\"authenticated\":true,\"identityId\":\"$STUB_IDENTITY\",\"csrfToken\":\"$STUB_CSRF\",\"access_token\":\"$STUB_TOKEN\"}"
      fault session-header-secret && headers+=("X-Debug: $STUB_LEAK")
    else
      status=401
    fi ;;
  "$BFF/oauth2/authorization/dokene")
    status=302 new_sid="pre-$STUB_SESSION-$RANDOM"
    location="$KC/realms/dokene/protocol/openid-connect/auth?response_type=code&client_id=dokene-bff&state=$STUB_STATE&redirect_uri=$BFF/login/oauth2/code/dokene&nonce=$STUB_NONCE&code_challenge=$STUB_CHALLENGE&code_challenge_method=S256"
    fault auth-elsewhere && location="http://login.example.test:9443/other/$STUB_CODE?state=$STUB_STATE#$STUB_NONCE"
    fault auth-no-pkce && location="$KC/realms/dokene/protocol/openid-connect/auth?response_type=code&state=$STUB_STATE&nonce=$STUB_NONCE"
    headers+=("Location: $location") ;;
  "$KC/realms/dokene/protocol/openid-connect/auth?"*)
    if [[ "$url" != *code_challenge=* || "$url" == *code_challenge_method=plain* ]]; then
      status=302
      headers+=("Location: $BFF/login/oauth2/code/dokene?error=invalid_request&state=$STUB_STATE")
      fault pkce-not-refused && headers=("Location: $BFF/login/oauth2/code/dokene?code=$STUB_CODE&state=$STUB_STATE")
    else
      body="<form id=\"kc-form-login\" action=\"$KC/realms/dokene/login-actions/authenticate?session_code=$STUB_NONCE&amp;tab_id=t\" method=\"post\">"
    fi ;;
  "$KC/realms/dokene/login-actions/authenticate"*)
    user="${form[username]:-}" pw="${form[password]:-}" expected=""
    case "$user" in
      testuser) expected="$STUB_PW_OWNER" ;; testoperator) expected="$STUB_PW_OPERATOR" ;; testviewer) expected="$STUB_PW_VIEWER" ;;
    esac
    if [ -n "$expected" ] && [ "$pw" = "$expected" ]; then
      status=302 location="$BFF/login/oauth2/code/dokene?state=$STUB_STATE&code=$STUB_CODE"
      fault callback-host-token && location="http://$STUB_HOST_TOKEN.example.test/cb?code=$STUB_CODE"
      fault login-not-callback && location="$KC/realms/dokene/login-actions/required-action?execution=x&code=$STUB_CODE&state=$STUB_STATE"
      headers+=("Location: $location")
    else
      body="<form id=\"kc-form-login\" action=\"$KC/realms/dokene/login-actions/authenticate?s=1\" method=\"post\">"
    fi ;;
  "$BFF/login/oauth2/code/dokene"*)
    status=302 new_sid="$STUB_SESSION"
    location="$BFF/"
    fault redirect-secret && location="$BFF/welcome?leak=$STUB_LEAK"
    fault redirect-secret-path && location="$BFF/$STUB_LEAK/home"
    fault redirect-token && location="$BFF/#access_token=$STUB_TOKEN"
    headers+=("Location: $location")
    fault callback-header-secret && headers+=("X-Debug: $STUB_LEAK")
    fault callback-header-token && headers+=("X-Debug: id_token=$STUB_TOKEN")
    printf '%s' "$new_sid" > "$STUB_DIR/active" ;;
  "$BFF/logout")
    if active && [ "$csrf" = "$STUB_CSRF" ]; then
      status=204
      rm -f "$STUB_DIR/active"
      if fault logout-no-expiry; then
        headers+=("Set-Cookie: JSESSIONID=$sid; Path=/; HttpOnly")
      else
        headers+=("Set-Cookie: JSESSIONID=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly")
      fi
    else
      status=403
    fi ;;
  *) status=404 ;;
esac
if [ -n "$new_sid" ]; then
  headers+=("Set-Cookie: JSESSIONID=$new_sid; Path=/; HttpOnly; SameSite=Lax")
  if [ -n "$jar_out" ]; then
    { grep -v JSESSIONID "$jar_out" 2>/dev/null; printf 'localhost\tFALSE\t/\tFALSE\t0\tJSESSIONID\t%s\n' "$new_sid"; } > "$jar_out.new"
    mv "$jar_out.new" "$jar_out"
  fi
fi
if [ -n "$dump" ]; then
  { printf 'HTTP/1.1 %s X\r\n' "$status"; for h in "${headers[@]}"; do printf '%s\r\n' "$h"; done; printf '\r\n'; } > "$dump"
fi
if [ -n "$out" ]; then printf '%s' "$body" > "$out"; else printf '%s' "$body"; fi
[ "$write" = "%{http_code}" ] && printf '%s' "$status"
exit 0
STUB
chmod +x "$TMP/bin/curl"

# run_e2e FAULT [KC_SECRET] [LEAK]: runs the copied script against the stub; output in $TMP/out, status in RUN_STATUS.
# KC_SECRET is the secret the stub Keycloak accepts (default: the .env value); LEAK is what a leaking fault exposes.
run_e2e() {
  rm -f "$TMP/stub/active"; : > "$TMP/stub/argv"
  env -u SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET -u DOKENE_OIDC_CLIENT_SECRET -u BFF_URL \
    ${CALLER_ENV[@]+"${CALLER_ENV[@]}"} \
    PATH="$TMP/bin:$PATH" STUB_DIR="$TMP/stub" STUB_FAULT="$1" STUB_KC_SECRET="${2:-$ENV_SECRET}" \
    STUB_LEAK="${3:-$ENV_SECRET}" STUB_STATE="$STATE" STUB_NONCE="$NONCE" STUB_CHALLENGE="$CHALLENGE" \
    STUB_CODE="$CODE" STUB_CSRF="$CSRF" STUB_IDENTITY="$IDENTITY" STUB_TOKEN="$TOKEN" STUB_SESSION="$SESSION" STUB_HOST_TOKEN="$HOST_TOKEN" \
    STUB_PW_OWNER="$OWNER_PW" STUB_PW_OPERATOR="$OPERATOR_PW" STUB_PW_VIEWER="$VIEWER_PW" \
    bash "$REPO/scripts/verify-keycloak-bff-e2e.sh" > "$TMP/out" 2>&1
  RUN_STATUS=$?
}
CALLER_ENV=()

# no_sensitive_output: the output holds none of the synthetic sensitive values (searched literally).
no_sensitive_output() {
  ! grep -F -f <(printf '%s\n' "$ENV_SECRET" "$CALLER_SECRET" "$OWNER_PW" "$OPERATOR_PW" "$VIEWER_PW" "$STATE" \
      "$NONCE" "$CHALLENGE" "$CODE" "$CSRF" "$IDENTITY" "$TOKEN" "$SESSION" "$HOST_TOKEN") "$TMP/out" >/dev/null
}
failed_with() { [ "$RUN_STATUS" -ne 0 ] && grep -qF -- "$1" "$TMP/out"; }

# expect_failure NAME FAULT MESSAGE [KC_SECRET] [LEAK]: the run fails with MESSAGE and prints no sensitive value.
expect_failure() {
  local name="$1" fault="$2" message="$3"
  run_e2e "$fault" "${4:-}" "${5:-}"
  if failed_with "$message"; then pass "$name: fails with \"$message\""; else fail "$name: fails with \"$message\""; sed 's/^/     | /' "$TMP/out"; fi
  assert "$name: prints no synthetic sensitive value" no_sensitive_output
}

write_env

# --- the stub reproduces a healthy stack, so every failure below is caused by its one fault
run_e2e none
assert "a healthy stack passes all 12 steps" grep -q "SUCCESS: All 12 End-to-End BFF Security Assertions PASSED" "$TMP/out"
[ "$RUN_STATUS" -eq 0 ] || sed 's/^/     | /' "$TMP/out"
assert "the client secret check reports Keycloak accepted it" grep -q '^\[3/12\] .*OK (client authenticated' "$TMP/out"
assert "a passing run prints no synthetic sensitive value" no_sensitive_output
refute "the client secret never reaches a curl command line" grep -qF "$ENV_SECRET" "$TMP/stub/argv"

# --- failure paths: each prints a fixed label or a URL origin, never the value it found
expect_failure "wrong client secret" none "Keycloak refused the configured <OIDC client secret>: HTTP 401, error unauthorized_client" "$(synthetic other)"
expect_failure "client not allowed the grant" client-not-allowed "Keycloak refused the dokene-bff client for the authorization_code grant: HTTP 400, error unauthorized_client; a client configuration problem, not the secret"
refute "a client configuration problem is not blamed on the secret" grep -qF "refused the configured <OIDC client secret>" "$TMP/out"
expect_failure "redirect to another host" auth-elsewhere "is not the dokene realm authorization endpoint: http://login.example.test:9443 (path, query and fragment withheld)"
expect_failure "redirect without PKCE" auth-no-pkce "PKCE RFC 7636 S256 parameters missing from the redirect to http://localhost:8081 (path"
expect_failure "PKCE not refused" pkce-not-refused "a request with no code_challenge was not refused: HTTP 302, Location http://localhost:8080 (path"
expect_failure "callback redirect with a token as host" callback-host-token "the redirect to <host withheld: it has a label of 32 or more characters> (path, query and fragment withheld) is not the BFF callback"
expect_failure "login not redirected to the callback" login-not-callback "the redirect to http://localhost:8081 (path, query and fragment withheld) is not the BFF callback"
expect_failure "client secret in the post-login redirect query" redirect-secret "Forbidden value '<OIDC client secret>' exposed in post-login redirect URL to http://localhost:8080 (path"
expect_failure "client secret in the post-login redirect path" redirect-secret-path "Forbidden value '<OIDC client secret>' exposed in post-login redirect URL"
expect_failure "token in the post-login redirect" redirect-token "Forbidden value 'access_token' exposed in post-login redirect URL"
expect_failure "client secret in the callback headers" callback-header-secret "Forbidden value '<OIDC client secret>' exposed in callback response headers"
expect_failure "token in the callback headers" callback-header-token "Forbidden value 'id_token' exposed in callback response headers"
expect_failure "authenticated as a string" session-authenticated-string "Invalid session contract: authenticated not true, identityId present, csrfToken present; body withheld"
expect_failure "invalid session contract" session-invalid "Invalid session contract: authenticated not true, identityId present, csrfToken present; body withheld"
expect_failure "session body that is not JSON" session-not-json "Invalid session contract: response is not a single JSON object; body withheld"
expect_failure "session object followed by trailing garbage" session-trailing-garbage "Invalid session contract: response is not a single JSON object; body withheld"
expect_failure "two JSON values in the session body" session-two-objects "Invalid session contract: response is not a single JSON object; body withheld"
expect_failure "client secret in the session body" session-body-secret "Forbidden value '<OIDC client secret>' exposed in session response body"
expect_failure "token in the session body" session-body-token "Forbidden value 'access_token' exposed in session response body"
expect_failure "client secret in the session headers" session-header-secret "Forbidden value '<OIDC client secret>' exposed in session response headers"
expect_failure "logout cookie without expiry" logout-no-expiry "has neither Max-Age=0 nor an Expires date; header withheld"

# --- a token error field that reflects the secret is not printed: only registered OAuth error codes are
LETTER_SECRET="$(synthetic lettersecret | tr '0-9' 'g-p')"  # lowercase letters only, so it looks like an error code
write_env "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET=$LETTER_SECRET"
run_e2e token-error-reflects-secret "$LETTER_SECRET"
assert "a token error reflecting the secret fails with a fixed label" \
  failed_with "Expected HTTP 400 invalid_grant for an unusable code, got HTTP 400, error <unrecognized error>"
refute "a token error reflecting the secret does not print it" grep -qF "$LETTER_SECRET" "$TMP/out"
write_env

# --- literal matching: a value that only matches as a pattern is not reported as the secret
REGEX_SECRET="a.b*c[d]$(synthetic rx)"
write_env "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET=$REGEX_SECRET"
run_e2e session-body-secret "$REGEX_SECRET" "aXbbbcd${REGEX_SECRET#a.b\*c\[d\]}"
assert "a look-alike of a secret with pattern characters is not a match" grep -q "^SUCCESS" "$TMP/out"
run_e2e session-body-secret "$REGEX_SECRET" "$REGEX_SECRET"
assert "the secret with pattern characters itself is found" failed_with "Forbidden value '<OIDC client secret>' exposed in session response body"
refute "the secret with pattern characters is not printed" grep -qF "$REGEX_SECRET" "$TMP/out"
write_env

# --- the secret checked is the one the stack runs with: the caller's environment wins over .env, as in docker compose
CALLER_ENV=("SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET=$CALLER_SECRET")
run_e2e none "$CALLER_SECRET"
assert "an exported client secret is the one Keycloak is asked to accept" grep -q "^SUCCESS" "$TMP/out"
refute "the exported client secret never reaches a curl command line" grep -qF "$CALLER_SECRET" "$TMP/stub/argv"
expect_failure "a leak of the exported secret is caught" session-body-secret "Forbidden value '<OIDC client secret>' exposed in session response body" "$CALLER_SECRET" "$CALLER_SECRET"
expect_failure "an exported secret the stack does not use is refused" none "Keycloak refused the configured <OIDC client secret>" "$ENV_SECRET"
CALLER_ENV=()

run_e2e none "$ENV_SECRET"
assert "without an exported value the .env secret is used" grep -q "^SUCCESS" "$TMP/out"

write_env "DOKENE_OIDC_CLIENT_SECRET=$ENV_SECRET"
run_e2e none "$ENV_SECRET"
assert "the oidc-bridge name is a fallback" grep -q "^SUCCESS" "$TMP/out"

write_env ""
run_e2e none
assert "without any client secret the script stops before calling the stack" failed_with "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET is not set"
assert "and makes no request" [ ! -s "$TMP/stub/argv" ]

# --- compose gives the BFF and Keycloak the client secret from the variable the script reads first
SECRET_VAR=SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET
assert "compose gives the BFF its client secret from $SECRET_VAR" \
  grep -qE "^ +$SECRET_VAR: \\$\\{$SECRET_VAR:\\?" "$ROOT_DIR/compose.yaml"
assert "compose gives Keycloak's oidc-bridge the same variable" \
  grep -qE "^ +DOKENE_OIDC_CLIENT_SECRET: \\$\\{$SECRET_VAR:-\\}" "$ROOT_DIR/compose.yaml"

# --- static guard: failure messages print no raw URL, session body or cookie header. A value passed to redact_url or
# is allowed, since only the URL's origin comes out.
SCRIPT="${E2E_SCRIPT_UNDER_TEST:-$ROOT_DIR/scripts/verify-keycloak-bff-e2e.sh}"
raw_value_in_failure_message() {
  grep -E 'echo "FAILED' "$SCRIPT" | sed -E 's/\$\(redact_url "[^"]*"\)//g' | grep -oE 'echo "FAILED[^"]*"' \
    | grep -E '\$\{?(KEYCLOAK_AUTH_URL|CALLBACK_URL|POST_LOGIN_REDIRECT|NO_PKCE_URL|SESSION_JSON|SET_COOKIE_HEADER|LOGOUT_COOKIE_HEADER|CSRF_TOKEN|IDENTITY_ID|TEST_CLIENT_SECRET|forbidden_val|location|FORM_ACTION|PRE_AUTH_SESSION_ID|POST_AUTH_SESSION_ID|LOGIN_URL)\b'
}
refute "no failure message prints a raw URL, JSON body or cookie header" raw_value_in_failure_message

if [ "$FAILURES" -gt 0 ]; then
  printf '\n%d check(s) failed\n' "$FAILURES"
  exit 1
fi
printf '\nall checks passed\n'
