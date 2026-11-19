#!/usr/bin/env bash
# Offline tests for scripts/lib/bff-login.sh: target validation, header and form-action parsing, and the
# fail-closed behaviour of bff_login. A stub curl on PATH stands in for the stack; no network or Docker is used.
# The `bash -c` snippets below expand their variables in the child shell, on purpose.
# shellcheck disable=SC2016
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
# Appends its argv, one call per line, and the content of every -K config it is given, under $REC_DIR.
printf '%s\n' "$*" >> "$REC_DIR/argv"
while [ $# -gt 0 ]; do
  [ "$1" = -K ] && cat "$2" >> "$REC_DIR/config" && shift
  shift
done
STUB
chmod +x "$TMP/cpbin/curl"
export CP_DIR="$TMP/cp"; mkdir -p "$CP_DIR"
export REC_DIR="$CP_DIR"
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

# Without a curl executable, curl_installed is false although `command -v curl` finds the wrapper, and the wrapper
# fails with status 127 and a clear message instead of a misleading connection error (#327, F8).
mkdir -p "$TMP/nocurl"
assert "curl-protected: command -v finds the wrapper even without a curl executable" \
  bash -c '. "$1"; PATH="$2"; command -v curl >/dev/null' _ "$ROOT_DIR/scripts/lib/curl-protected.sh" "$TMP/nocurl"
refute "curl-protected: curl_installed is false without a curl executable" \
  bash -c '. "$1"; PATH="$2"; curl_installed' _ "$ROOT_DIR/scripts/lib/curl-protected.sh" "$TMP/nocurl"
assert "curl-protected: curl_installed is true with one" bash -c '. "$1"; PATH="$2:$PATH"; curl_installed' _ \
  "$ROOT_DIR/scripts/lib/curl-protected.sh" "$TMP/cpbin"
bash -c '. "$1"; PATH="$2"; curl -s http://localhost:8080/' _ "$ROOT_DIR/scripts/lib/curl-protected.sh" "$TMP/nocurl" \
  2> "$TMP/nocurl-err"; CP_RC=$?
assert "curl-protected: the wrapper exits 127 and names the missing executable" \
  bash -c '[ "$1" -eq 127 ] && grep -q "no curl executable is on PATH" "$2"' _ "$CP_RC" "$TMP/nocurl-err"
# Scripts that load the wrapper check for curl with curl_installed or type -P, never `command -v curl`.
cp_checks=""
for script in "$ROOT_DIR"/scripts/*.sh; do
  grep -qE '^\. "\$ROOT_DIR/scripts/lib/(bff-login|curl-protected)\.sh"' "$script" || continue
  grep -Ev '^[[:space:]]*#' "$script" | grep -qE 'command -v curl|have curl|which curl' && cp_checks+=" ${script##*/}"
done
assert "curl-protected: no script that loads the wrapper checks curl with command -v${cp_checks:+ (found in:$cp_checks)}" \
  [ -z "$cp_checks" ]

# sends_session_values FILE: true when FILE, outside full-line comments, names a CSRF header, a JSESSIONID cookie or
# a callback code, however the value is quoted or assembled in that file (#401).
sends_session_values() {
  grep -nE '' "$1" | grep -vE '^[0-9]+:[[:space:]]*#' \
    | grep -qiE 'X-CSRF-TOKEN|JSESSIONID|oauth2/code/[A-Za-z0-9_-]+\?code='
}
# A script that writes its own curl config (`-K -` or `-K <(…)`) for every such request says so with this marker.
OWN_CONFIG_MARKER='^# curl-protected: own -K config\.'

# Every live script that sends a CSRF header, a session cookie or a callback code loads the wrapper, directly or
# through bff-login.sh, or carries the own-config marker, so a new call site cannot reintroduce the exposure unnoticed.
cp_unwrapped=""
for script in "$ROOT_DIR"/scripts/*.sh; do
  sends_session_values "$script" || continue
  grep -qE "$OWN_CONFIG_MARKER" "$script" && continue
  # shellcheck disable=SC2016  # expanded by the child shell, or a literal regex
  grep -qE '^\. "\$ROOT_DIR/scripts/lib/(bff-login|curl-protected)\.sh"' "$script" || cp_unwrapped+=" ${script##*/}"
done
assert "curl-protected: every script sending session values loads the wrapper${cp_unwrapped:+ (missing:$cp_unwrapped)}" \
  [ -z "$cp_unwrapped" ]

# --- evidence scripts under docs/verification load the wrapper relative to their own path (#401)
# They cd elsewhere and source env files that are not in the repository, so they cannot run here. Each one's loader
# line is run as written, with ${BASH_SOURCE[0]} replaced by the script's path, and representative helpers are taken
# from the scripts as written and run against an argv-recording curl stub with synthetic values.
# shellcheck disable=SC2016  # literal regexes and patterns
EV_LOADER_RE='^\. "\$\(cd "\$\(dirname "\$\{BASH_SOURCE\[0\]\}"\)/\.\.(/\.\.)*" && pwd\)/scripts/lib/curl-protected\.sh" \|\| \{'
# shellcheck disable=SC2016
EV_SOURCE_VAR='${BASH_SOURCE[0]}'
# ev_loader FILE: FILE's loader line, reading its own path from $EV_SCRIPT.
ev_loader() {
  local line
  line="$(grep -m1 -E "$EV_LOADER_RE" "$1")" || return 1
  printf '%s\n' "${line//"$EV_SOURCE_VAR"/\$EV_SCRIPT}"
}
# ev_fn FILE NAME: the lines that define function NAME in FILE, up to the first line where bash can parse them.
ev_fn() {
  local -a lines
  local start n text=""
  start="$(grep -nE "^$2\(\) *\{" "$1" | head -1 | cut -d: -f1)"
  [ -n "$start" ] || return 1
  mapfile -t lines < "$1"
  for ((n = start - 1; n < ${#lines[@]}; n++)); do
    text+="${lines[n]}"$'\n'
    # Only a line holding a closing brace can end the definition, so only those are parsed.
    [[ "${lines[n]}" == *"}"* ]] && bash -n <<<"$text" 2>/dev/null && { printf '%s' "$text"; return 0; }
  done
  return 1
}

# The recording stub of the curl-protected checks above records these runs under $EV_DIR.
export EV_DIR="$TMP/ev"; mkdir -p "$EV_DIR" "$TMP/evq"
# Synthetic values, built at run time; the evidence scripts read them from these globals.
EV_TOKEN="tok-$(printf '%s' 5281)"
export EV_TOKEN BFF="http://localhost:8080" QA_TMP="$TMP/evq" TENANT="tenant-5282" A="tenant-5282" RUN="r1" \
  cid="cust-5283" V="v1" CSRF="$EV_TOKEN" IK="ik-1" JAR="$TMP/evq/jar-testoperator.txt"

# ev_run FILE NAME ARGS...: loads the wrapper with FILE's own loader, defines helper NAME from FILE and runs it, with
# the stub as the only curl and stand-ins for the env-file helpers (jq answers every read with the synthetic token).
ev_run() {
  local rel="$1" file="$ROOT_DIR/$1" name="$2" loader fn
  shift 2
  : > "$EV_DIR/argv"; : > "$EV_DIR/config"  # first, so a helper that cannot run leaves no earlier recording
  if ! loader="$(ev_loader "$file")" || ! fn="$(ev_fn "$file" "$name")"; then
    fail "evidence $name: the loader and helper are found in $rel"
    return 1
  fi
  # shellcheck disable=SC2034,SC2329  # EV_SCRIPT and the stand-ins are used by the code given to eval
  (
    set +u  # the evidence scripts do not run under set -u
    EV_SCRIPT="$file"
    eval "$loader" || exit 1
    PATH="$TMP/cpbin:$PATH" REC_DIR="$EV_DIR"
    jq() { cat > /dev/null; printf '%s\n' "$EV_TOKEN"; }
    api() { :; }; etag() { printf 'e1\n'; }; chk() { :; }; leak() { printf '0\n'; }
    eval "$fn"
    "$name" "$@"
    wait
  ) > /dev/null 2>&1
  return 0
}
# ev_check LABEL CALLS: the calls were recorded, each with a config, and no token, tenant or URL is on any argv.
ev_check() {
  assert "evidence $1: curl ran $2 time(s)" [ "$(wc -l < "$EV_DIR/argv")" -eq "$2" ]
  refute "evidence $1: every curl call reads a -K config" grep -qv '^-K ' "$EV_DIR/argv"
  refute "evidence $1: no token or URL on curl's argv" grep -qE '5281|localhost' "$EV_DIR/argv"
  assert "evidence $1: the URLs reach curl in the config" grep -qF 'url = "http://localhost:8080/api/' "$EV_DIR/config"
}
ev_check_csrf() {
  assert "evidence $1: the CSRF token reaches curl in the config" \
    grep -qxF "header = \"X-CSRF-TOKEN: $EV_TOKEN\"" "$EV_DIR/config"
}

ev_run docs/verification/issue-121-evidence/scripts/sec121-i.sh mkt t1
ev_check "sec121-i mkt (session read, then a POST with the CSRF token)" 2; ev_check_csrf "sec121-i mkt"
ev_run docs/verification/issue-121-evidence/scripts/sec121-i.sh bg_call testoperator "$cid" "$TMP/evq/out"
ev_check "sec121-i bg_call (POST in a background subshell)" 2; ev_check_csrf "sec121-i bg_call"
ev_run docs/verification/issue-121-evidence/scripts/sec121-l.sh prov testuser "QA 5284"
ev_check "sec121-l prov (token read in a nested command substitution)" 2; ev_check_csrf "sec121-l prov"
ev_run docs/verification/issue-121-evidence/scripts/sec121-m.sh call testoperator
ev_check "sec121-m call" 2; ev_check_csrf "sec121-m call"
ev_run docs/verification/issue-246-253-evidence/scripts/ret246.sh hit /api/customers POST '{}'
ev_check "ret246 hit (CSRF header from an optional expansion)" 1; ev_check_csrf "ret246 hit"
ev_run docs/verification/issue-246-evidence/res09-retest.sh call "label" "$BFF/api/customers" -H "X-CSRF-TOKEN: $CSRF"
ev_check "res09-retest call (caller-supplied curl arguments)" 1; ev_check_csrf "res09-retest call"
ev_run docs/verification/issue-123-evidence/scripts/res09.sh code /api/customers
ev_check "res09 code" 1

# Every evidence script that sends a CSRF header, a JSESSIONID value or a callback code loads the wrapper before its
# first cd or curl call; the loader finds this repository's wrapper and fails closed when the wrapper is missing.
ev_missing="" ev_late="" ev_broken="" ev_open="" ev_seen=0
while IFS= read -r script; do
  sends_session_values "$script" || continue
  ev_seen=$((ev_seen + 1)); rel="${script#"$ROOT_DIR"/}"
  if ! loader="$(ev_loader "$script")"; then ev_missing+=" $rel"; continue; fi
  at="$(grep -nE -m1 "$EV_LOADER_RE" "$script" | cut -d: -f1)"
  # The first cd, curl, `.` or `source` outside a full-line comment, which must not come before the loader.
  first="$(grep -nE '' "$script" | grep -vE '^[0-9]+:[[:space:]]*#' \
    | grep -E '^[0-9]+:(.*[;&|({[:space:]])?(cd|\.|source)[[:space:]]|(^[0-9]+:|.*[^A-Za-z0-9_-])curl([^A-Za-z0-9_-]|$)' \
    | cut -d: -f1 | head -1)"
  [ -n "$first" ] && [ "$first" -lt "$at" ] && ev_late+=" $rel"
  # shellcheck disable=SC2016  # expanded by the child shell
  bash -c 'EV_SCRIPT=$1; eval "$2" && [ "$(type -t curl)" = function ]' _ "$script" "$loader" > /dev/null 2>&1 \
    || ev_broken+=" $rel"
  # shellcheck disable=SC2016  # expanded by the child shell
  mkdir -p "$TMP/fakerepo/$(dirname "$rel")"  # a checkout whose directories exist but whose wrapper is missing
  out="$(bash -c 'EV_SCRIPT=$1; eval "$2"; echo continued' _ "$TMP/fakerepo/$rel" "$loader" 2>&1)"
  [[ "$out" == *"curl-protected.sh not found"* && "$out" != *continued* ]] || ev_open+=" $rel"
done < <(find "$ROOT_DIR/docs/verification" -name '*.sh' | sort)
assert "evidence scripts: the scan found the scripts that send session values ($ev_seen)" [ "$ev_seen" -ge 20 ]
assert "evidence scripts: each loads the wrapper relative to its own path${ev_missing:+ (missing:$ev_missing)}" \
  [ -z "$ev_missing" ]
assert "evidence scripts: the loader runs before any cd or curl${ev_late:+ (late:$ev_late)}" [ -z "$ev_late" ]
assert "evidence scripts: the loader finds this repository's wrapper${ev_broken:+ (broken:$ev_broken)}" [ -z "$ev_broken" ]
assert "evidence scripts: a missing wrapper stops the script with a message${ev_open:+ (fails open:$ev_open)}" \
  [ -z "$ev_open" ]

if [ "$FAILURES" -gt 0 ]; then printf '\n%s test(s) failed\n' "$FAILURES"; exit 1; fi
printf '\nall tests passed\n'
