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
# Records its arguments and stdin; answers by the URL it is given.
url="" headers="" out="" post=false
while [ $# -gt 0 ]; do
  case "$1" in
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
  printf 'HTTP/1.1 302 Found\r\nLocation: %s\r\n' "$STUB_CALLBACK" > "$headers"
  exit 0
fi
case "$url" in
  */oauth2/authorization/dokene)
    printf 'HTTP/1.1 302 Found\r\nLocation: %s\r\n' "$STUB_AUTH_URL" > "$headers"
    printf '302' ;;
  "$STUB_AUTH_URL")
    printf '<form action="%s" method="post">' "$STUB_FORM_ACTION" > "$out" ;;
esac
exit 0
STUB
chmod +x "$TMP/bin/curl"

run_login() {
  : > "$TMP/calls"; rm -f "$TMP/posted-password"
  printf '%s' 'p@ss word&=1' | PATH="$TMP/bin:$PATH" STUB_LOG="$TMP/calls" STUB_DIR="$TMP" \
    bff_login testuser "$TMP/jar" "$TMP" > "$TMP/out" 2> "$TMP/err"
}
export STUB_AUTH_URL STUB_FORM_ACTION STUB_CALLBACK
use_local_stack() {
  STUB_AUTH_URL="http://localhost:8081/realms/dokene/protocol/openid-connect/auth?x=1"
  STUB_FORM_ACTION="http://localhost:8081/realms/dokene/login-actions/authenticate?s=1"
  STUB_CALLBACK="http://localhost:8080/login/oauth2/code/dokene?code=c&state=s"
}

use_local_stack
assert "a loopback flow logs in" run_login
assert "a successful login prints the cookie jar" [ "$(cat "$TMP/out")" = "$TMP/jar" ]
assert "the password reaches curl through stdin" [ "$(cat "$TMP/posted-password")" = 'p@ss word&=1' ]
assert "the callback is completed" grep -qx "http://localhost:8080/login/oauth2/code/dokene?code=c&state=s" "$TMP/calls"
refute "transient login files are removed" compgen -G "$TMP/bff-login-*"

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

# --- static safety: the helper never puts the password in an argument
refute "the helper never passes a password on the command line" grep -Eq 'password=\$' "$LIB"

if [ "$FAILURES" -gt 0 ]; then printf '\n%s test(s) failed\n' "$FAILURES"; exit 1; fi
printf '\nall tests passed\n'
