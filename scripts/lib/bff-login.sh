# shellcheck shell=bash
# ==============================================================================
# scripts/lib/bff-login.sh
#
# Shared Keycloak/BFF browser login for the local QA scripts. Source it; do not execute it.
#
#   printf '%s' "$PASSWORD" | bff_login USERNAME COOKIE_JAR WORK_DIR
#
# Drives the Authorization Code flow the way a browser does: BFF /oauth2/authorization/dokene,
# the Keycloak login form, the credential POST and the BFF callback. On success the authenticated
# session is in COOKIE_JAR and its path is printed on stdout. On failure it prints a short reason
# (never the password or a response body) on stderr and returns 1.
#
# Safety:
#   - every hop (authorization redirect, login form action, OIDC callback) must be a plain-HTTP
#     loopback URL, so credentials are only ever posted to the local stack;
#   - the password is read from stdin and handed to curl through stdin (--data-urlencode
#     "password@-"), so it never appears in an argument list or the process table;
#   - the callback must be BFF_URL's own /login/oauth2/code/dokene endpoint, so the code only goes back
#     to the origin the login started from.
#
# Reads BFF_URL (default http://localhost:8080). WORK_DIR holds transient header and page files.
# Sourcing this file also sources curl-protected.sh, so the authorization, form and callback URLs (the callback holds
# the authorization code) and every header or cookie value of the caller's later curl calls stay off curl's command
# line (issue 327).
# Pure parts (is_local_target, header_value, bff_login_form_action) are tested offline by
# scripts/tests/bff-login-test.sh.
# ==============================================================================

# shellcheck source=curl-protected.sh
. "$(dirname "${BASH_SOURCE[0]}")/curl-protected.sh"

# True for a URL on a plain-HTTP loopback origin, with or without a path (OIDC redirects and the login form).
is_local_target() {
  case "$1" in *[[:cntrl:]]*) return 1 ;; esac  # one line only: a newline could smuggle a second URL
  [[ "$1" =~ ^http://(localhost|127\.0\.0\.1)(:[0-9]{1,5})?(/|\?|$) ]]
}

# Value of response header $1 (case-insensitive) in header dump $2; last occurrence wins (after redirects).
header_value() {
  awk -v k="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" '
    { line = $0; sub(/\r$/, "", line); i = index(line, ":"); if (i == 0) next
      if (tolower(substr(line, 1, i - 1)) == k) { v = substr(line, i + 1); sub(/^[ \t]+/, "", v); val = v } }
    END { print val }' "$2"
}

# The first form action in HTML page $1, with &amp; decoded; empty when the page has none.
bff_login_form_action() {
  grep -o 'action="[^"]*"' "$1" 2>/dev/null | head -n 1 | cut -d'"' -f2 | sed 's/&amp;/\&/g'
}

# bff_login USERNAME COOKIE_JAR WORK_DIR, password on stdin. See the header for the contract.
bff_login() {
  local user="$1" jar="$2" work="$3" headers page rc
  headers="$(mktemp "$work/bff-login.XXXXXX")" || { printf 'bff_login: cannot create a work file for %s\n' "$user" >&2; return 1; }
  page="$(mktemp "$work/bff-login.XXXXXX")" || { rm -f "$headers"; printf 'bff_login: cannot create a work file for %s\n' "$user" >&2; return 1; }
  BFF_LOGIN_ERROR=""
  _bff_login_flow "$user" "$jar" "$headers" "$page"; rc=$?
  rm -f "$headers" "$page"
  if [ "$rc" -ne 0 ]; then
    printf 'bff_login: %s for %s\n' "${BFF_LOGIN_ERROR:-login failed}" "$user" >&2
    return 1
  fi
  printf '%s\n' "$jar"
}

# The four hops of bff_login; on failure sets BFF_LOGIN_ERROR and returns 1. Reads the password from stdin.
_bff_login_flow() {
  local user="$1" jar="$2" headers="$3" page="$4"
  local bff="${BFF_URL:-http://localhost:8080}" status auth_url form_action callback

  status="$(curl -s -c "$jar" -D "$headers" -o /dev/null -w '%{http_code}' "$bff/oauth2/authorization/dokene" \
    </dev/null)" || { BFF_LOGIN_ERROR="BFF unreachable"; return 1; }
  [ "$status" = "302" ] || { BFF_LOGIN_ERROR="authorization request returned HTTP $status, expected 302"; return 1; }
  auth_url="$(header_value location "$headers")"
  is_local_target "$auth_url" || { BFF_LOGIN_ERROR="authorization redirect is not a loopback URL"; return 1; }

  curl -s -c "$jar" -b "$jar" -o "$page" "$auth_url" </dev/null || { BFF_LOGIN_ERROR="Keycloak unreachable"; return 1; }
  form_action="$(bff_login_form_action "$page")"
  is_local_target "$form_action" || { BFF_LOGIN_ERROR="login form action is missing or not a loopback URL"; return 1; }

  # The password goes through stdin, so it never appears in the process list, and only to a loopback target.
  : > "$headers"
  curl -s -c "$jar" -b "$jar" -D "$headers" -o /dev/null -X POST "$form_action" \
    --data-urlencode "username=$user" --data-urlencode "password@-" || { BFF_LOGIN_ERROR="credential POST failed"; return 1; }
  callback="$(header_value location "$headers")"
  [ -n "$callback" ] || { BFF_LOGIN_ERROR="authentication failed (no redirect; check the user and password)"; return 1; }
  is_local_target "$callback" || { BFF_LOGIN_ERROR="authentication failed (callback is not a loopback URL)"; return 1; }
  case "$callback" in
    "$bff/login/oauth2/code/dokene?"*) ;;
    *) BFF_LOGIN_ERROR="authentication failed (unexpected callback)"; return 1 ;;
  esac

  curl -s -c "$jar" -b "$jar" -o /dev/null "$callback" </dev/null || { BFF_LOGIN_ERROR="callback exchange failed"; return 1; }
}
