#!/usr/bin/env bash
# Offline tests for scripts/lib/bff-login.sh: target validation, header and form-action parsing, and the
# fail-closed behaviour of bff_login. A stub curl on PATH stands in for the stack; no network or Docker is used.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIB="$ROOT_DIR/scripts/lib/bff-login.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

FAILURES=0
pass() { printf 'ok   %s\n' "$1"; }
fail() { printf 'FAIL %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
assert() { local name="$1"; shift; if "$@"; then pass "$name"; else fail "$name"; fi; }
refute() { local name="$1"; shift; if "$@"; then fail "$name"; else pass "$name"; fi; }

# shellcheck source=../lib/bff-login.sh
. "$LIB"

# --- credentials are only posted to loopback targets
assert "a loopback login form is a local target" is_local_target "http://localhost:8081/realms/dokene/login-actions/authenticate?x=1"
assert "127.0.0.1 is a local target" is_local_target "http://127.0.0.1:8080/login/oauth2/code/dokene?code=x"
assert "a bare loopback origin is a local target" is_local_target "http://localhost"
assert "a query right after the origin is a local target" is_local_target "http://localhost:8080?x=1"
refute "https is not a local target" is_local_target "https://localhost:8081/x"
refute "a remote login form is not a local target" is_local_target "http://login.example.com/realms/dokene"
refute "a host that starts with localhost is not a local target" is_local_target "http://localhost.example.com/x"
refute "a port cannot carry a hostname" is_local_target "http://localhost:8081.example.com/x"
refute "userinfo cannot smuggle a target" is_local_target "http://localhost:8081@example.com/x"
refute "a relative action is not a local target" is_local_target "/realms/dokene/login-actions/authenticate"
refute "an empty value is not a local target" is_local_target ""
refute "a newline cannot smuggle a second URL" is_local_target "$(printf 'http://localhost:8081/x\nhttp://evil.example.com/')"
refute "a trailing newline is not a local target" is_local_target $'http://localhost:8081/x\n'
refute "a carriage return is not a local target" is_local_target $'http://localhost:8081/x\r'
refute "a tab is not a local target" is_local_target "$(printf 'http://localhost:8081/\tx')"

# --- headers are matched case-insensitively and the last one wins
printf 'HTTP/1.1 302 Found\r\nLocation: http://first\r\n\r\nHTTP/1.1 200 OK\r\nlocation: http://second\r\n' > "$TMP/h"
assert "the last Location wins" [ "$(header_value Location "$TMP/h")" = "http://second" ]
assert "a missing header is empty" [ -z "$(header_value x-missing "$TMP/h")" ]

# --- the login form action is parsed and &amp; decoded
printf '<form id="kc-form-login" action="http://localhost:8081/realms/dokene/login-actions/authenticate?a=1&amp;b=2" method="post">\n<form action="http://other">' > "$TMP/page"
assert "the first form action is used, &amp; decoded" \
  [ "$(bff_login_form_action "$TMP/page")" = "http://localhost:8081/realms/dokene/login-actions/authenticate?a=1&b=2" ]
printf '<html>no form</html>' > "$TMP/noform"
assert "a page without a form yields an empty action" [ -z "$(bff_login_form_action "$TMP/noform")" ]
assert "a missing page yields an empty action" [ -z "$(bff_login_form_action "$TMP/absent")" ]

# --- bff_login against a stub curl: each hop is controlled by STUB_* variables
mkdir -p "$TMP/bin"
cat > "$TMP/bin/curl" <<'STUB'
#!/usr/bin/env bash
# Records its arguments and stdin; answers by the URL it is given. bff-login.sh sources curl-protected.sh, so the URL
# arrives in a -K config (`url = "…"`), which the stub reads like curl does.
printf '%s\n' "$*" >> "$STUB_DIR/argv"
url="" headers="" out="" post=false
while [ $# -gt 0 ]; do
  case "$1" in
    -K) while IFS= read -r line; do
          case "$line" in 'url = "'*'"') line="${line#url = \"}"; url="${line%\"}" ;; esac
        done < "$2"; shift ;;
    -D) headers="$2"; shift ;;
    -o) out="$2"; shift ;;
    -c|-b|-w|--data-urlencode) shift ;;
    -X) [ "$2" = POST ] && post=true; shift ;;
    -*) ;;
    *) url="$1" ;;
  esac
  shift
done
printf '%s\n' "$url" >> "$STUB_LOG"
if [ "$post" = true ]; then
  cat > "$STUB_DIR/posted-password"
  printf 'HTTP/1.1 %s\r\n' "${STUB_CALLBACK:+302 Found}${STUB_CALLBACK:-200 OK}" > "$headers"
  [ -z "$STUB_CALLBACK" ] || printf 'Location: %s\r\n' "$STUB_CALLBACK" >> "$headers"
  exit 0
fi
case "$url" in
  */oauth2/authorization/dokene)
    printf 'HTTP/1.1 %s\r\nLocation: %s\r\n' "${STUB_STATUS:-302}" "$STUB_AUTH_URL" > "$headers"
    printf '%s' "${STUB_STATUS:-302}" ;;
  "$STUB_AUTH_URL")
    printf '<form action="%s" method="post">' "$STUB_FORM_ACTION" > "$out" ;;
esac
exit 0
STUB
chmod +x "$TMP/bin/curl"

# run_login [USER]: logs in through the stub with WORK_DIR=$TMP/work.
run_login() {
  : > "$TMP/calls"; : > "$TMP/argv"; rm -f "$TMP/posted-password"; mkdir -p "$TMP/work"
  printf '%s' 'p@ss word&=1' | PATH="$TMP/bin:$PATH" STUB_LOG="$TMP/calls" STUB_DIR="$TMP" \
    bff_login "${1:-testuser}" "$TMP/jar" "$TMP/work" > "$TMP/out" 2> "$TMP/err"
}
unset BFF_URL  # the default http://localhost:8080 is the origin the stub callback must match
export STUB_AUTH_URL STUB_FORM_ACTION STUB_CALLBACK STUB_STATUS
use_local_stack() {
  STUB_STATUS=302
  STUB_AUTH_URL="http://localhost:8081/realms/dokene/protocol/openid-connect/auth?x=1"
  STUB_FORM_ACTION="http://localhost:8081/realms/dokene/login-actions/authenticate?s=1"
  STUB_CALLBACK="http://localhost:8080/login/oauth2/code/dokene?code=c&state=s"
}

use_local_stack
assert "a loopback flow logs in" run_login
assert "a successful login prints the cookie jar" [ "$(cat "$TMP/out")" = "$TMP/jar" ]
assert "the password reaches curl through stdin" [ "$(cat "$TMP/posted-password")" = 'p@ss word&=1' ]
assert "the callback is completed" grep -qx "http://localhost:8080/login/oauth2/code/dokene?code=c&state=s" "$TMP/calls"
assert "curl was called for every hop" [ "$(wc -l < "$TMP/argv")" -eq 4 ]
refute "no URL, and so no authorization code, is on a curl command line (#327)" grep -qE 'https?://|code=c' "$TMP/argv"
refute "transient login files are removed" compgen -G "$TMP/work/bff-login.*"

use_local_stack; STUB_AUTH_URL="http://keycloak.example.com/realms/dokene/auth"
refute "a remote authorization redirect is refused" run_login
assert "a refused redirect explains why" grep -q "authorization redirect is not a loopback URL" "$TMP/err"
refute "nothing is fetched from a remote authorization redirect" grep -q example.com "$TMP/calls"

use_local_stack; STUB_FORM_ACTION="http://evil.example.com/collect"
refute "a remote login form action is refused" run_login
refute "no password is posted to a remote form action" [ -e "$TMP/posted-password" ]
refute "a refused login prints no cookie jar" [ -s "$TMP/out" ]

use_local_stack; STUB_CALLBACK="http://localhost:8081/realms/dokene/login-actions/authenticate?error=1"
refute "a callback that is not the BFF code endpoint is refused" run_login
assert "a failed authentication explains why" grep -q "authentication failed" "$TMP/err"

use_local_stack; STUB_CALLBACK="http://attacker.example.com/login/oauth2/code/dokene?code=c"
refute "a remote callback is refused" run_login
refute "a remote callback is never followed" grep -q attacker.example.com "$TMP/calls"
refute "the password never appears in the error output" grep -q 'p@ss' "$TMP/err"

use_local_stack; STUB_STATUS=200
refute "an authorization request that is not a 302 is refused" run_login
assert "a non-302 authorization request explains why" grep -q "expected 302" "$TMP/err"
refute "no password is posted after a non-302 authorization request" [ -e "$TMP/posted-password" ]

use_local_stack; STUB_CALLBACK=""
refute "a credential POST without a redirect (wrong password) is refused" run_login
assert "a missing redirect points at the credentials" grep -q "check the user and password" "$TMP/err"

use_local_stack; STUB_CALLBACK="http://localhost:9999/x?r=/login/oauth2/code/dokene&code=c"
refute "a loopback callback on another origin is refused" run_login
refute "a callback on another origin is never followed" grep -q "localhost:9999" "$TMP/calls"

use_local_stack; STUB_CALLBACK="http://localhost:8080/elsewhere?r=/login/oauth2/code/dokene&code=c"
refute "a callback with the endpoint only in its query is refused" run_login

# A username with path segments must not name files: "x/../../escape" once resolved to $TMP/escape.*.
use_local_stack; mkdir -p "$TMP/work/bff-login-x"; printf keep > "$TMP/escape.headers"
assert "a username with path segments still logs in" run_login "x/../../escape"
assert "a username cannot overwrite or delete a file outside WORK_DIR" [ "$(cat "$TMP/escape.headers" 2>/dev/null)" = keep ]
refute "no work files are left after that login" compgen -G "$TMP/work/bff-login.*"

# --- static safety: the helper sends the password only through stdin (comments are ignored)
LIB_CODE="$(grep -Ev '^[[:space:]]*#' "$LIB")"
assert "the helper posts the password from stdin exactly once" [ "$(grep -c 'password@-' <<<"$LIB_CODE")" -eq 1 ]
refute "the helper never builds a password= argument" grep -q 'password=' <<<"$LIB_CODE"

# --- curl-protected.sh (sourced by bff-login.sh): headers, cookie values and URLs go to curl in a -K config (#327)
mkdir -p "$TMP/cpbin"
cat > "$TMP/cpbin/curl" <<'STUB'
#!/usr/bin/env bash
# Records its argv and the content of every -K config it is given.
printf '%s\n' "$*" > "$CP_DIR/argv"
: > "$CP_DIR/config"
while [ $# -gt 0 ]; do
  [ "$1" = -K ] && cat "$2" >> "$CP_DIR/config" && shift
  shift
done
STUB
chmod +x "$TMP/cpbin/curl"
export CP_DIR="$TMP/cp"; mkdir -p "$CP_DIR"
cp_run() { rm -f "$CP_DIR/argv" "$CP_DIR/config"; PATH="$TMP/cpbin:$PATH" curl "$@"; }
# Synthetic values, built at run time.
CP_TOKEN="tok-$(printf '%s' 4271)\"q\\b"; CP_SID="sid-$(printf '%s' 4272)"; CP_CODE="code-$(printf '%s' 4273)"
cp_run -s -X POST -b "JSESSIONID=$CP_SID" -b "$TMP/jar" -H "X-CSRF-TOKEN: $CP_TOKEN" -H "@$TMP/hdr" -d 'http://data' \
  -o /dev/null "http://localhost:8080/login/oauth2/code/dokene?code=$CP_CODE&state=s" --url "http://localhost:8080/u2" \
  -- "http://localhost:8080/u3"
# shellcheck disable=SC2016  # expanded by the child shell, or a literal regex
assert "curl-protected: no token, session id, code or URL on curl's argv" \
  bash -c '! grep -qE "4271|4272|4273|localhost" "$1"' _ "$CP_DIR/argv"
assert "curl-protected: jar files, @file headers and option values stay on argv" \
  grep -qF -- "-b $TMP/jar -H @$TMP/hdr -d http://data -o /dev/null" "$CP_DIR/argv"
assert "curl-protected: the header reaches curl escaped in the config" \
  grep -qxF "header = \"X-CSRF-TOKEN: tok-4271\\\"q\\\\b\"" "$CP_DIR/config"
assert "curl-protected: the cookie value reaches curl in the config" grep -qxF "cookie = \"JSESSIONID=$CP_SID\"" "$CP_DIR/config"
assert "curl-protected: the callback URL, --url and the URL after -- reach curl in the config, in order" \
  [ "$(grep '^url = ' "$CP_DIR/config" | tr '\n' ' ')" = "url = \"http://localhost:8080/login/oauth2/code/dokene?code=$CP_CODE&state=s\" url = \"http://localhost:8080/u2\" url = \"http://localhost:8080/u3\" " ]
cp_run -s -H $'X-Evil: a\r\nX-Injected: 1' "http://localhost:8080/x" 2> "$TMP/cp-err"; CP_RC=$?
assert "curl-protected: a header with a line break is refused with status 2" [ "$CP_RC" -eq 2 ]
refute "curl-protected: curl is not run for a refused header" [ -e "$CP_DIR/argv" ]
cp_run -s -o /dev/null -b "$TMP/jar"
assert "curl-protected: a call with nothing to move runs curl without a config" [ "$(cat "$CP_DIR/argv")" = "-s -o /dev/null -b $TMP/jar" ]
# shellcheck disable=SC2016  # expanded by the child shell, or a literal regex
assert "curl-protected: the wrapper runs under set -u with no argument at all" \
  bash -c 'set -u; . "$1"; PATH="$2:$PATH" curl' _ "$ROOT_DIR/scripts/lib/curl-protected.sh" "$TMP/cpbin"

# Every live script that sends a CSRF header or a session cookie by value loads the wrapper, directly or through
# bff-login.sh, so a new call site cannot reintroduce the exposure unnoticed.
cp_unwrapped=""
for script in "$ROOT_DIR"/scripts/*.sh; do
  grep -qE 'X-CSRF-TOKEN: ?\$|-b "?JSESSIONID=' "$script" || continue
  # shellcheck disable=SC2016  # expanded by the child shell, or a literal regex
  grep -qE '^\. "\$ROOT_DIR/scripts/lib/(bff-login|curl-protected)\.sh"' "$script" || cp_unwrapped+=" ${script##*/}"
done
assert "curl-protected: every script sending session values loads the wrapper${cp_unwrapped:+ (missing:$cp_unwrapped)}" \
  [ -z "$cp_unwrapped" ]

if [ "$FAILURES" -gt 0 ]; then printf '\n%s test(s) failed\n' "$FAILURES"; exit 1; fi
printf '\nall tests passed\n'
