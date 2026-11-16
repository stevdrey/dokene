# shellcheck shell=bash
# ABOUTME: Helpers for manual QA suite #333 (Phase 3 environment and WhatsApp readiness): logins through the shared
# ABOUTME: BFF login helper, tenant-scoped API calls with CSRF, and a sanitized recorder. Synthetic data only.
# shellcheck disable=SC2034,SC2046  # sourced library sets CODE/BODY/KC for callers; rec results are single words

#
# Source it from the other scripts (QA_HOME defaults to ~/qa333; WT is the candidate worktree).
# Never prints tokens, cookies, CSRF values or passwords. Bodies are printed only through jq filters chosen by the
# caller (non-secret fields).
# Headers, cookie values and URLs (callback code, CSRF token) stay off curl's command line (issue 327, invariant 35).
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)/scripts/lib/curl-protected.sh" || { echo "scripts/lib/curl-protected.sh not found" >&2; return 1 2>/dev/null || exit 1; }

QA_HOME=${QA_HOME:-$HOME/qa333}
WT=${WT:-$QA_HOME/dokene-qa333}
export COMPOSE_PROJECT_NAME=dokene-qa333
set -a; . "$WT/.env"; set +a
. "$WT/scripts/lib/bff-login.sh"
. "$WT/scripts/lib/test-passwords.sh"
BFF=${BFF_URL:-http://localhost:8080}
KC=http://localhost:${KEYCLOAK_PORT:-8081}
QA_TMP=${QA_TMP:-$QA_HOME/tmp}; mkdir -p "$QA_TMP"; chmod 700 "$QA_TMP"
OUTD=${OUTD:-$QA_HOME/out}; mkdir -p "$OUTD"
RESULTS=${RESULTS:-$OUTD/results.tsv}
EXTRA_PW_FILE=$QA_HOME/.extra-users-pw   # one generated password for the four extra synthetic users (mode 600)

pw_for() {
  case "$1" in
    testuser|testoperator|testviewer) test_password_for "$1" ;;
    testadmin|testowner2|testoperator2|testnone) cat "$EXTRA_PW_FILE" ;;
    *) echo "no password for $1" >&2; return 1 ;;
  esac
}

qa_login() { # user -> session in $QA_TMP/jar-<user>.txt
  local pw; pw=$(pw_for "$1") || return 1
  rm -f "$QA_TMP/jar-$1.txt"
  printf '%s' "$pw" | bff_login "$1" "$QA_TMP/jar-$1.txt" "$QA_TMP" >/dev/null
}
session() { curl -s -b "$QA_TMP/jar-$1.txt" -c "$QA_TMP/jar-$1.txt" "$BFF/api/session"; }
csrf() { session "$1" | jq -r .csrfToken; }
identity() { session "$1" | jq -r .identityId; }

# req <user|-> <tenant|-> <METHOD> <path> <json|-> [extra curl args] -> CODE HDRS BODY
req() {
  local u=$1 t=$2 m=$3 p=$4 d=$5; shift 5; local -a a=(); local out tok
  if [ "$u" != "-" ]; then
    a+=(-b "$QA_TMP/jar-$u.txt" -c "$QA_TMP/jar-$u.txt")
    tok=$(csrf "$u")
    case ${CSRF_MODE:-} in "") [ "$tok" != null ] && a+=(-H "X-CSRF-TOKEN: $tok");; none) ;; esac
  fi
  [ "$t" != "-" ] && a+=(-H "X-Tenant-Id: $t")
  if [ "$d" != "-" ]; then printf '%s' "$d" > "$QA_TMP/body.json"; a+=(-H 'Content-Type: application/json' --data-binary "@$QA_TMP/body.json"); fi
  out=$(curl -s -i -X "$m" "${a[@]}" "$@" "$BFF$p" 2>/dev/null)
  CODE=$(printf '%s\n' "$out" | head -1 | awk '{print $2}')
  HDRS=$(printf '%s\n' "$out" | sed '/^\r\{0,1\}$/q')
  BODY=$(printf '%s\n' "$out" | sed '1,/^\r\{0,1\}$/d')
}
etag() { printf '%s\n' "$HDRS" | grep -i '^etag:' | tr -d '\r' | awk '{print $2}'; }
hdr() { printf '%s\n' "$HDRS" | grep -i "^$1:" | tr -d '\r' | cut -d' ' -f2-; }
rid() { hdr x-request-id; }

# rec <case> <check> <PASS|FAIL|BLOCKED|NOTE> <note>   (manual/API observations only; sanitized notes)
rec() { printf '%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$(date -u +%FT%TZ)" "$4" | tee -a "$RESULTS"; }
psqlq() { docker compose -f "$WT/compose.yaml" exec -T postgres psql -U "$DOKENE_DB_BOOTSTRAP_USERNAME" -d "$DOKENE_DB_NAME" -At -c "$1"; }

# kc_admin_token: Keycloak master-realm admin token (password on stdin, never in an argument list).
kc_admin_token() {
  printf '%s' "$KC_BOOTSTRAP_ADMIN_PASSWORD" | curl -s -d grant_type=password -d client_id=admin-cli \
    -d "username=$KC_BOOTSTRAP_ADMIN_USERNAME" --data-urlencode "password@-" \
    "$KC/realms/master/protocol/openid-connect/token" | jq -r '.access_token // empty'
}
# stub_sends: provider send calls the Graph stub logged; exit 3 when its log is missing (a count must never be empty).
stub_sends() {
  local log=$QA_HOME/stub/requests.log
  [ -f "$log" ] || { echo "stub log $log missing" >&2; return 3; }
  grep -c 'kind=send' "$log"; true
}
# assert_safe_stack <true|false>: the backend's Graph base URL is the loopback stub and its global switch is as expected.
assert_safe_stack() {
  local base global
  base=$(docker compose -f "$WT/compose.yaml" exec -T backend sh -c 'printf %s "$DOKENE_INTEGRATION_WHATSAPP_GRAPH_BASE_URL"')
  global=$(docker compose -f "$WT/compose.yaml" exec -T backend sh -c 'printf %s "$DOKENE_MESSAGING_OUTBOUND_ENABLED"')
  [ "$base" = http://127.0.0.1:18080 ] && [ "$global" = "$1" ] \
    || { echo "unsafe stack: base=${base:-unset} global=${global:-unset} (expected loopback stub, global=$1)" >&2; return 1; }
}
