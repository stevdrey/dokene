#!/usr/bin/env bash
# ABOUTME: Runs the follow-up disposition operator journey against the local stack and writes a journey record.
# ABOUTME: Logs in as the three seeded QA users, each with its own password, and targets only loopback origins.
# ==============================================================================
# verify-journey-follow-up-disposition.sh
#
# Runnable verification of one operator journey against the local stack (Issue #375):
# an OPERATOR finds a due follow-up in the queue and dismisses it, and the queue and
# the eligibility API agree afterwards. Along the way it checks role isolation, cross-tenant
# denial, If-Match handling, idempotent replay, and that no outbound message was created.
#
# Procedure and expected results: .agents/skills/operator-journey-verification/journeys/follow-up-disposition.md
#
# Usage:
#   ./scripts/verify-journey-follow-up-disposition.sh [run] [--out DIR] [--keep-fixture]
#   ./scripts/verify-journey-follow-up-disposition.sh --fixture-only [--out DIR]
#     (builds and keeps a due fixture for the manual browser step S21, runs no steps)
#   ./scripts/verify-journey-follow-up-disposition.sh doctor
#   ./scripts/verify-journey-follow-up-disposition.sh help
#
# Prerequisites: ./scripts/dev-env.sh up --seed (local stack, seeded QA identities), curl, jq.
# Targets only localhost. All data is invented; nothing is sent to WhatsApp or any provider.
#
# Exit codes: 0 every step passed (with --fixture-only: the fixture was built and kept, no step ran); 1 a step failed; 2 a precondition is unavailable
# (stack down, seed missing, non-local target) or the journey record could not be written:
# the journey is blocked, not passed.
# ==============================================================================

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
JOURNEY="follow-up disposition: dismiss a due follow-up from the queue"
ISOLATION_TENANT_NAME="QA Journey Isolation"
ISOLATION_TENANT_KEY="qa-journey-isolation"
SEED_TENANT_NAME="QA Café Norte"

usage() {
  awk 'NR > 1 && /^#/ { print; if (/^# =+$/ && ++rules == 2) exit } NR > 1 && !/^#/ { exit }' "${BASH_SOURCE[0]}" \
    | sed 's/^# \{0,1\}//'
}

# ------------------------------------------------------------------------------
# Pure helpers (exercised offline by scripts/tests/verify-journey-follow-up-disposition-test.sh)
# ------------------------------------------------------------------------------

# True only for plain-HTTP loopback origins (no path, query or fragment): the journey must never reach a shared or
# production host. The loopback rule itself lives only in is_local_target.
is_local_url() {
  is_local_target "$1" && [[ "$1" =~ ^http://[^/?#]*$ ]]
}

# True for a URL on a plain-HTTP loopback origin, with or without a path (OIDC redirects and the login form).
is_local_target() {
  case "$1" in *[[:cntrl:]]*) return 1 ;; esac  # one line only: a newline could smuggle a second URL
  [[ "$1" =~ ^http://(localhost|127\.0\.0\.1)(:[0-9]{1,5})?(/|\?|$) ]]
}

# True when $1 (absolute) is $2 or below it.
path_is_within() {
  case "$1/" in "$2"/*) return 0 ;; *) return 1 ;; esac
}

# True when $1 or one of its existing ancestors is the same directory as $2 (-ef), which also catches case-insensitive
# file systems and bind mounts that a string comparison misses.
path_is_within_dir() {
  local p="$1"
  while :; do
    [ -e "$p" ] && [ "$p" -ef "$2" ] && return 0
    [ "$p" = "/" ] || [ -z "$p" ] && return 1
    p="${p%/*}"; [ -n "$p" ] || p="/"
  done
}

# Physical absolute form of $1 without GNU realpath: resolves symlinks through the nearest existing ancestor and
# appends the missing rest. Fails when the missing rest contains a ".." component (it cannot be resolved safely).
resolve_path() {
  local path="$1" rest="" base
  case "$path" in /*) ;; *) path="$PWD/$path" ;; esac
  while [ ! -d "$path" ]; do
    base="${path##*/}"
    case "$base" in ..) return 1 ;; .|"") ;; *) rest="/$base$rest" ;; esac
    path="${path%/*}"; [ -n "$path" ] || path="/"
  done
  path="$(cd "$path" && pwd -P)" || return 1
  printf '%s%s\n' "${path%/}" "$rest"
}

# Customers matching $2 in a queue page $1, or "invalid" unless .items is an array.
queue_match_count() {
  jq -er --arg id "$2" 'if (.items | type) == "array" then [.items[] | select(.customerId == $id)] | length
    else error("no items array") end' "$1" 2>/dev/null || echo invalid
}

# Messages in a page $1, or "invalid" unless .items is an array (a missing field must not read as zero).
message_count() {
  jq -er 'if (.items | type) == "array" then .items | length else error("no items array") end' "$1" 2>/dev/null \
    || echo invalid
}

# Double-quoted YAML scalar with backslashes, quotes and control characters neutralized.
yaml_str() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="$(printf '%s' "$s" | tr '\t\r\n' '   ' | tr -d '\000-\037\177')"
  printf '"%s"' "$s"
}

# Value of response header $1 (case-insensitive) in header dump $2; last occurrence wins (after redirects).
header_value() {
  awk -v k="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" '
    { line = $0; sub(/\r$/, "", line); i = index(line, ":"); if (i == 0) next
      if (tolower(substr(line, 1, i - 1)) == k) { v = substr(line, i + 1); sub(/^[ \t]+/, "", v); val = v } }
    END { print val }' "$2"
}

# One step: id, action, expected, actual, result (passed|failed|not-run), kind.
# Only status codes, enum values, counts and booleans are ever passed as "actual": never names, phones or bodies.
STEPS_PASSED=0
STEPS_FAILED=0
STEPS_NOT_RUN=0
STEPS_YAML=""
record_step() {
  local id="$1" action="$2" expected="$3" actual="$4" result="$5" kind="${6:-automated}"
  case "$result" in
    passed)  STEPS_PASSED=$((STEPS_PASSED + 1)); printf '  [pass] %s %s\n' "$id" "$action" ;;
    failed)  STEPS_FAILED=$((STEPS_FAILED + 1)); printf '  [FAIL] %s %s\n         expected: %s\n         actual:   %s\n' \
               "$id" "$action" "$expected" "$actual" ;;
    not-run) STEPS_NOT_RUN=$((STEPS_NOT_RUN + 1)); printf '  [not-run] %s %s (%s)\n' "$id" "$action" "$actual" ;;
    *) printf 'record_step: unknown result %s\n' "$result" >&2; return 2 ;;
  esac
  STEPS_YAML="${STEPS_YAML}  - id: ${id}
    action: $(yaml_str "$action")
    expected: $(yaml_str "$expected")
    actual: $(yaml_str "$actual")
    kind: ${kind}
    result: ${result}
"
}

# check ID ACTION EXPECTED ACTUAL CONDITION...: records passed when CONDITION (a command) succeeds.
check() {
  local id="$1" action="$2" expected="$3" actual="$4"; shift 4
  if "$@"; then record_step "$id" "$action" "$expected" "$actual" passed
  else record_step "$id" "$action" "$expected" "$actual" failed; fi
}

eq() { [ "$1" = "$2" ]; }
is_id() { [ -n "$1" ] && [ "$1" != "null" ]; }
# Synthetic Chilean mobile for the fixture from a random unsigned integer $1. Only the +5698 block is used: all of its
# 10,000,000 numbers pass PhoneNormalizer (libphonenumber 9.0.43, isValidNumberForRegion CL), checked exhaustively;
# other +569 blocks contain rejected ranges (e.g. +56969..., +56991...).
fixture_phone() { printf '+5698%07d\n' "$(( $1 % 10000000 ))"; }
# A replay (status $1) returned the original dismissal: HTTP 200 and the same real id in both responses ($2, $3).
same_dismissal() { [ "$1" = "200" ] && is_id "$2" && is_id "$3" && [ "$2" = "$3" ]; }
is_due() { [ "$1" = "DUE" ] || [ "$1" = "OVERDUE" ]; }
# Only a successful read with a recognized non-due status counts; an error such as status-500 does not.
not_due() { [ "$1" = "NOT_YET_DUE" ] || [ "$1" = "INELIGIBLE" ]; }

# ------------------------------------------------------------------------------
# Journey record
# ------------------------------------------------------------------------------
write_record() {
  local result="passed"
  [ "$STEPS_FAILED" -gt 0 ] && result="failed"
  [ "${FIXTURE_ONLY:-false}" = true ] && result="not-run"
  [ -n "${BLOCKED_REASON:-}" ] && result="blocked"
  rm -f "$RECORD" 2>/dev/null
  {
    echo "# Journey run record (format: docs/verification/pstack-applicability-audit.md#evidence-formats)."
    echo "# Sanitized: status codes, enum values and counts only; ids are replaced by placeholders."
    echo "journey: $(yaml_str "$JOURNEY")"
    echo "run_id: $(yaml_str "$RUN_ID")"
    echo "commit: $(yaml_str "$(git -C "$ROOT_DIR" describe --always --dirty 2>/dev/null || echo unknown)")"
    echo "target: $(yaml_str "$BFF_URL")"
    echo "fixture: $(yaml_str "seed: ./scripts/dev-env.sh up --seed; invented customer 'QA Journey $RUN_ID' with a random +5698 number in '$SEED_TENANT_NAME'; isolation tenant '$ISOLATION_TENANT_NAME' (idempotent)")"
    echo "actors:"
    echo "  - \"testoperator: OPERATOR in tenant A ($SEED_TENANT_NAME), not a member of tenant B\""
    echo "  - \"testviewer: VIEWER in tenant A\""
    echo "  - \"testuser: OWNER of tenant A and tenant B ($ISOLATION_TENANT_NAME)\""
    echo "result: $result"
    [ -n "${BLOCKED_REASON:-}" ] && echo "blocked_reason: $(yaml_str "$BLOCKED_REASON")"
    echo "summary: { passed: $STEPS_PASSED, failed: $STEPS_FAILED, not_run: $STEPS_NOT_RUN }"
    echo "steps:"
    printf '%s' "$STEPS_YAML"
    echo "outbound_sends: ${OUTBOUND_COUNT:-unknown}"
    echo "cleanup: $(yaml_str "${CLEANUP_NOTE:-no fixture was created}")"
  } > "$RECORD" 2>/dev/null
  # The group's status is only its last command's, so require the complete record on disk instead.
  # The current run_id line rules out a complete record left by an earlier run that could not be replaced.
  if ! { [ -f "$RECORD" ] && tail -n 1 "$RECORD" | grep -q '^cleanup: ' \
      && grep -qxF "run_id: $(yaml_str "$RUN_ID")" "$RECORD"; }; then
    printf '[blocked] could not write the journey record to %s\n' "$RECORD" >&2
    return 1
  fi
  printf '\nJourney record: %s\n' "$RECORD"
}

# first_missing_test_password: prints the first DOKENE_TEST_<ROLE>_PASSWORD that is empty or unset, or nothing.
first_missing_test_password() {
  local key
  for key in DOKENE_TEST_OWNER_PASSWORD DOKENE_TEST_OPERATOR_PASSWORD DOKENE_TEST_VIEWER_PASSWORD; do
    [ -n "${!key:-}" ] || { printf '%s' "$key"; return 0; }
  done
}

# submit_credentials USER JAR FORM_ACTION: posts the login form with that actor's own password (issue 314). The password
# goes through stdin, so it never appears in the process list. An unknown user posts nothing.
submit_credentials() {
  local password
  password="$(test_password_for "$1")" || return 1
  printf '%s' "$password" | curl -s -c "$2" -b "$2" -D "$WORK_DIR/login-headers" -o /dev/null -X POST \
    "$3" --data-urlencode "username=$1" --data-urlencode "password@-"
}

# Sourcing defines the helpers without running the journey (offline tests).
# shellcheck disable=SC2317
if [ "${BASH_SOURCE[0]}" != "$0" ]; then return 0 2>/dev/null || true; fi

# ------------------------------------------------------------------------------
# Configuration
# ------------------------------------------------------------------------------
COMMAND="run"
OUT_DIR=""
KEEP_FIXTURE=false
FIXTURE_ONLY=false
while [ $# -gt 0 ]; do
  case "$1" in
    run|doctor) COMMAND="$1" ;;
    help|-h|--help) usage; exit 0 ;;
    --out) [ $# -ge 2 ] || { echo "--out needs a directory" >&2; exit 2; }; OUT_DIR="$2"; shift ;;
    --keep-fixture) KEEP_FIXTURE=true ;;
    --fixture-only) FIXTURE_ONLY=true; KEEP_FIXTURE=true ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 2 ;;
  esac
  shift
done

if [ -f "$ROOT_DIR/.env" ]; then
  # .env is sourced in a subshell, as seed-local-qa.sh does, so quoting matches; only the requested value comes back
  # to this process.
  env_get() { ( set -a; . "$ROOT_DIR/.env" >/dev/null 2>&1; printf '%s' "${!1:-}" ); }
  # Each actor has its own password (issue 314). A value already in the environment wins over .env.
  for key in DOKENE_TEST_OWNER_PASSWORD DOKENE_TEST_OPERATOR_PASSWORD DOKENE_TEST_VIEWER_PASSWORD; do
    [ -n "${!key:-}" ] || printf -v "$key" '%s' "$(env_get "$key")"
  done
  # shellcheck disable=SC2031 # env_get's subshell only reads .env; the value comes back on stdout
  : "${KEYCLOAK_PORT:=$(env_get KEYCLOAK_PORT)}"
fi
KEYCLOAK_PORT="${KEYCLOAK_PORT:-8081}"
BFF_URL="${BFF_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:${KEYCLOAK_PORT}}"

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$(od -An -N3 -tx1 /dev/urandom | tr -d ' \n')"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/dokene-journey-work.XXXXXX")"
[ -n "$OUT_DIR" ] || OUT_DIR="${TMPDIR:-/tmp}/dokene-journey-$RUN_ID"
# Refuse an evidence directory inside the repository before anything is created there (it would change the candidate).
OUT_DIR="$(resolve_path "$OUT_DIR")" || { echo "[blocked] cannot resolve --out; avoid '..' below a missing directory" >&2; rm -rf "$WORK_DIR"; exit 2; }
if path_is_within "$OUT_DIR" "$ROOT_DIR" || path_is_within_dir "$OUT_DIR" "$ROOT_DIR"; then
  echo "[blocked] --out is inside the repository; keep evidence outside the candidate worktree" >&2
  rm -rf "$WORK_DIR"; exit 2
fi
mkdir -p "$OUT_DIR" || { echo "Cannot create $OUT_DIR" >&2; rm -rf "$WORK_DIR"; exit 2; }
RECORD="$OUT_DIR/journey-record.yaml"

CUSTOMER_ID=""
TENANT_A=""
BLOCKED_REASON=""

blocked() {
  BLOCKED_REASON="$1"
  printf '[blocked] %s\n' "$1" >&2
  archive_fixture
  write_record || true
  exit 2
}

# ------------------------------------------------------------------------------
# HTTP
# ------------------------------------------------------------------------------
# http METHOD PATH JAR [curl args...]: sets HTTP_STATUS, HTTP_ETAG, HTTP_REQUEST_ID; the body is in $WORK_DIR/body.
# The response body never leaves $WORK_DIR, which is deleted on exit.
http() {
  local method="$1" path="$2" jar="$3"; shift 3
  : > "$WORK_DIR/headers"
  HTTP_STATUS="$(curl -s -b "$jar" -D "$WORK_DIR/headers" -o "$WORK_DIR/body" -w '%{http_code}' \
    --max-time 20 -X "$method" "$BFF_URL$path" "$@")" || HTTP_STATUS="000"
  HTTP_ETAG="$(header_value etag "$WORK_DIR/headers")"
  HTTP_REQUEST_ID="$(header_value x-request-id "$WORK_DIR/headers")"
}
body() { jq -r "$1" "$WORK_DIR/body" 2>/dev/null; }

# Failure trace for the last request: status and request id only (grep it in `./scripts/dev-env.sh logs backend`).
trace() { printf 'HTTP %s, X-Request-Id %s' "$HTTP_STATUS" "${HTTP_REQUEST_ID:-none}"; }

login() {
  local user="$1" jar="$WORK_DIR/$1.jar" auth_url form_action callback
  curl -s -c "$jar" -D "$WORK_DIR/login-headers" -o /dev/null "$BFF_URL/oauth2/authorization/dokene" || return 1
  auth_url="$(header_value location "$WORK_DIR/login-headers")"
  is_local_target "$auth_url" || return 1
  curl -s -c "$jar" -b "$jar" -o "$WORK_DIR/login-page" "$auth_url" || return 1
  form_action="$(grep -o 'action="[^"]*"' "$WORK_DIR/login-page" | head -n 1 | cut -d'"' -f2 | sed 's/&amp;/\&/g')"
  is_local_target "$form_action" || return 1
  # The password goes through stdin, so it never appears in the process list, and only to a loopback target.
  submit_credentials "$user" "$jar" "$form_action" || return 1
  callback="$(header_value location "$WORK_DIR/login-headers")"
  is_local_target "$callback" || return 1
  case "$callback" in *"/login/oauth2/code/dokene"*) ;; *) return 1 ;; esac
  curl -s -c "$jar" -b "$jar" -o /dev/null "$callback" || return 1
  rm -f "$WORK_DIR/login-page"
  echo "$jar"
}

# queue_contains JAR TENANT CUSTOMER -> prints true/false, walking every page of the due queue.
queue_contains() {
  local jar="$1" tenant="$2" customer="$3" cursor="" pages=0 query count
  while :; do
    # A queue too long to walk is inconclusive, never "absent".
    [ $pages -lt 50 ] || { echo "inconclusive-page-cap"; return; }
    query="limit=100"; [ -n "$cursor" ] && query="$query&cursor=$(jq -rn --arg c "$cursor" '$c|@uri')"
    http GET "/api/follow-up-queue?$query" "$jar" -H "X-Tenant-Id: $tenant"
    [ "$HTTP_STATUS" = "200" ] || { echo "status-$HTTP_STATUS"; return; }
    count="$(queue_match_count "$WORK_DIR/body" "$customer")"
    case "$count" in
      invalid) echo "invalid-body"; return ;;
      0) ;;
      *) echo true; return ;;
    esac
    cursor="$(body '.nextCursor // empty')"
    [ -n "$cursor" ] || { echo false; return; }
    pages=$((pages + 1))
  done
}

# Archives the fixture customer unless --keep-fixture; records what actually happened in CLEANUP_NOTE.
archive_fixture() {
  [ -n "$CUSTOMER_ID" ] || return 0
  if [ "$KEEP_FIXTURE" = true ]; then
    CLEANUP_NOTE="fixture customer 'QA Journey $RUN_ID' kept (--keep-fixture or --fixture-only); archive it as testuser when done; work files removed; this record kept"
  else
    # Archiving needs CUSTOMER_DELETE, which OPERATOR lacks; the owner session archives the fixture.
    http GET "/api/customers/$CUSTOMER_ID" "$OWNER_JAR" -H "X-Tenant-Id: $TENANT_A"
    http DELETE "/api/customers/$CUSTOMER_ID" "$OWNER_JAR" -H "X-Tenant-Id: $TENANT_A" \
      -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "If-Match: $HTTP_ETAG"
    if [ "$HTTP_STATUS" = "204" ]; then
      CLEANUP_NOTE="fixture customer archived (DELETE /api/customers/{customer} 204); work files removed; this record kept; tenant B kept for reruns"
    else
      CLEANUP_NOTE="fixture customer NOT archived ($(trace)); archive 'QA Journey $RUN_ID' manually; this record kept"
    fi
  fi
  CUSTOMER_ID=""
}

cleanup() {
  local status=$?
  archive_fixture
  rm -rf "$WORK_DIR"
  exit "$status"
}
trap cleanup EXIT

# ------------------------------------------------------------------------------
# Doctor: preconditions. Any failure means blocked (exit 2), never passed.
# ------------------------------------------------------------------------------
doctor() {
  local code missing
  is_local_url "$BFF_URL" || blocked "BFF_URL is not a plain loopback origin; this journey only runs against the local stack"
  is_local_url "$KEYCLOAK_URL" || blocked "KEYCLOAK_URL is not a plain loopback origin"
  command -v curl >/dev/null 2>&1 || blocked "curl is required"
  command -v jq >/dev/null 2>&1 || blocked "jq is required"
  missing="$(first_missing_test_password)"
  [ -z "$missing" ] || blocked "$missing is not set; run ./scripts/dev-env.sh up, which generates it in .env"
  # shellcheck source=lib/test-passwords.sh
  . "$ROOT_DIR/scripts/lib/test-passwords.sh"
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$KEYCLOAK_URL/realms/dokene/.well-known/openid-configuration")"
  [ "$code" = "200" ] || blocked "Keycloak discovery returned HTTP $code; run ./scripts/dev-env.sh up --seed"
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$BFF_URL/api/session")"
  [ "$code" = "401" ] || blocked "BFF /api/session returned HTTP $code (expected 401); run ./scripts/dev-env.sh up --seed"
  echo "[ok] local stack reachable ($BFF_URL)"
}

doctor
if [ "$COMMAND" = "doctor" ]; then
  echo "[ok] doctor passed; run without 'doctor' to execute the journey"
  trap - EXIT; rm -rf "$WORK_DIR"; rmdir "$OUT_DIR" 2>/dev/null; exit 0
fi

echo "=== Journey: $JOURNEY (run $RUN_ID) ==="

# ------------------------------------------------------------------------------
# Fixture
# ------------------------------------------------------------------------------
OWNER_JAR="$(login testuser)" || blocked "login failed for testuser; check DOKENE_TEST_OWNER_PASSWORD and the seed"
OPERATOR_JAR="$(login testoperator)" || blocked "login failed for testoperator; run ./scripts/dev-env.sh seed"
VIEWER_JAR="$(login testviewer)" || blocked "login failed for testviewer; run ./scripts/dev-env.sh seed"
http GET /api/session "$OWNER_JAR"; OWNER_CSRF="$(body .csrfToken)"
http GET /api/session "$OPERATOR_JAR"; OPERATOR_CSRF="$(body .csrfToken)"
http GET /api/session "$VIEWER_JAR"; VIEWER_CSRF="$(body .csrfToken)"
[ -n "$OWNER_CSRF" ] && [ -n "$OPERATOR_CSRF" ] && [ -n "$VIEWER_CSRF" ] || blocked "no CSRF token in /api/session"

http GET /api/tenants "$OPERATOR_JAR"
TENANT_A="$(jq -r --arg n "$SEED_TENANT_NAME" '[.[] | select(.displayName == $n)][0].tenantId // empty' "$WORK_DIR/body")"
[ -n "$TENANT_A" ] || blocked "seeded workspace '$SEED_TENANT_NAME' not visible to testoperator; run ./scripts/dev-env.sh seed"

# Tenant B: provisioned idempotently by the owner; testoperator is never invited, so reruns keep the same shape.
http POST /api/tenants "$OWNER_JAR" -H "Content-Type: application/json" -H "X-CSRF-TOKEN: $OWNER_CSRF" \
  -H "Idempotency-Key: $ISOLATION_TENANT_KEY" \
  -d "$(jq -cn --arg n "$ISOLATION_TENANT_NAME" --arg k "$ISOLATION_TENANT_KEY" '{displayName: $n, idempotencyKey: $k}')"
case "$HTTP_STATUS" in 200|201) ;; *) blocked "isolation tenant provisioning returned $(trace); is DOKENE_PROVISIONING_ENABLED=true?" ;; esac
TENANT_B="$(body .tenantId)"
is_id "$TENANT_B" || blocked "isolation tenant provisioning returned no tenant id ($(trace))"

http GET /api/follow-up-policy "$OPERATOR_JAR" -H "X-Tenant-Id: $TENANT_A"
TENANT_TZ="$(body .timeZone)"
[ "$HTTP_STATUS" = "200" ] && [ -n "$TENANT_TZ" ] || blocked "tenant follow-up policy unavailable ($(trace))"
TODAY="$(TZ="$TENANT_TZ" date +%F)"
PURCHASED_AT="$(date -u -d '-40 days' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v-40d +%Y-%m-%dT%H:%M:%SZ)"
PHONE="$(fixture_phone "$(od -An -N4 -tu4 /dev/urandom | tr -d ' \n')")"

A=(-H "X-Tenant-Id: $TENANT_A")
OP_WRITE=("${A[@]}" -H "X-CSRF-TOKEN: $OPERATOR_CSRF" -H "Content-Type: application/json")

http POST /api/customers "$OPERATOR_JAR" "${OP_WRITE[@]}" \
  -d "$(jq -cn --arg n "QA Journey $RUN_ID" --arg p "$PHONE" '{displayName: $n, phones: [{number: $p, region: "CL", primary: true}]}')"
[ "$HTTP_STATUS" = "201" ] || blocked "fixture customer creation returned $(trace)"
NEW_CUSTOMER_ID="$(body .id)"; PHONE_ID="$(body '.phones[0].id')"
{ is_id "$NEW_CUSTOMER_ID" && is_id "$PHONE_ID"; } || blocked "fixture customer creation returned no customer or phone id ($(trace))"
CUSTOMER_ID="$NEW_CUSTOMER_ID"
CLEANUP_NOTE="fixture customer left active (cleanup did not run)"

http GET "/api/customers/$CUSTOMER_ID/contact-policy" "$OPERATOR_JAR" "${A[@]}"
http PUT "/api/customers/$CUSTOMER_ID/contacts/$PHONE_ID/consents/WHATSAPP" "$OPERATOR_JAR" "${OP_WRITE[@]}" \
  -H "If-Match: $HTTP_ETAG" -d '{"status":"GRANTED","source":"CUSTOMER_VERBAL"}'
[ "$HTTP_STATUS" = "200" ] || blocked "fixture consent returned $(trace)"

http POST "/api/customers/$CUSTOMER_ID/purchases" "$OPERATOR_JAR" "${OP_WRITE[@]}" \
  -H "Idempotency-Key: qa-journey-purchase-$RUN_ID" \
  -d "$(jq -cn --arg t "$PURCHASED_AT" '{purchasedAt: $t, description: "Compra sintetica QA"}')"
[ "$HTTP_STATUS" = "201" ] || blocked "fixture purchase returned $(trace)"

http GET "/api/customers/$CUSTOMER_ID/follow-up-policy" "$OPERATOR_JAR" "${A[@]}"
# This version is current now and superseded by the PUT below: S13 sends it as a genuinely stale If-Match.
OLD_POLICY_ETAG="$HTTP_ETAG"
http PUT "/api/customers/$CUSTOMER_ID/follow-up-policy" "$OPERATOR_JAR" "${OP_WRITE[@]}" -H "If-Match: $HTTP_ETAG" \
  -d "$(jq -cn --arg d "$TODAY" '{cadenceDays: null, explicitNextDate: $d}')"
[ "$HTTP_STATUS" = "200" ] || blocked "fixture follow-up policy returned $(trace)"
echo "[ok] fixture ready (customer {customer} in tenant A, next date = tenant today)"

if [ "$FIXTURE_ONLY" = true ]; then
  for n in $(seq 1 20); do
    record_step "S$n" "automated step" "see the journey file" "fixture-only run" not-run >/dev/null
  done
  record_step S21 "the follow-up queue screen reflects the dismissal after reload" \
    "customer absent from the queue page" "fixture kept for the browser step" not-run manual
  archive_fixture
  echo "Fixture for S21: customer 'QA Journey $RUN_ID' in '$SEED_TENANT_NAME' is due and kept; archive it as testuser afterwards."
  write_record || exit 2
  exit 0
fi

# ------------------------------------------------------------------------------
# Steps
# ------------------------------------------------------------------------------
eligibility_status() {  # jar tenant -> prints status enum, or status-<code>
  http GET "/api/customers/$CUSTOMER_ID/follow-up-eligibility" "$1" -H "X-Tenant-Id: $2"
  if [ "$HTTP_STATUS" = "200" ]; then body .status; else echo "status-$HTTP_STATUS"; fi
}
current_policy_etag() {
  http GET "/api/customers/$CUSTOMER_ID/follow-up-policy" "$OPERATOR_JAR" "${A[@]}"
  printf '%s' "$HTTP_ETAG"
}
dismiss() {  # jar csrf tenant if-match key
  local args=(-H "X-Tenant-Id: $3" -H "Content-Type: application/json" -H "Idempotency-Key: $5")
  [ -n "$2" ] && args+=(-H "X-CSRF-TOKEN: $2")
  [ -n "$4" ] && args+=(-H "If-Match: $4")
  http POST "/api/customers/$CUSTOMER_ID/follow-up-dismissals" "$1" "${args[@]}" -d '{"notes":"QA journey dismissal"}'
}

echo "--- Eligibility and queue (tenant A)"
S="$(eligibility_status "$OPERATOR_JAR" "$TENANT_A")"
check S1 "operator reads eligibility of the fixture customer" "DUE or OVERDUE" "$S" is_due "$S"
S="$(queue_contains "$OPERATOR_JAR" "$TENANT_A" "$CUSTOMER_ID")"
check S2 "operator sees the customer in the due queue" "true" "$S" eq "$S" true
S="$(queue_contains "$VIEWER_JAR" "$TENANT_A" "$CUSTOMER_ID")"
check S3 "viewer sees the same customer in the due queue (read-only role)" "true" "$S" eq "$S" true

echo "--- Role isolation"
ETAG="$(current_policy_etag)"
case "$ETAG" in \"[0-9]*\") ;; *) blocked "no strong numeric ETag on the customer follow-up policy" ;; esac
case "$OLD_POLICY_ETAG" in "$ETAG"|"") blocked "no superseded follow-up policy version to use as a stale If-Match" ;; esac
dismiss "$VIEWER_JAR" "$VIEWER_CSRF" "$TENANT_A" "$ETAG" "qa-journey-viewer-$RUN_ID"
check S4 "viewer dismissal with a current If-Match is refused" "403" "$(trace)" eq "$HTTP_STATUS" 403
S="$(eligibility_status "$OPERATOR_JAR" "$TENANT_A")"
check S5 "the refused viewer request changed nothing" "DUE or OVERDUE" "$S" is_due "$S"

echo "--- Session security (real BFF chain)"
dismiss "$OPERATOR_JAR" "" "$TENANT_A" "$ETAG" "qa-journey-nocsrf-$RUN_ID"
check S6 "operator dismissal without the CSRF token is refused" "403" "$(trace)" eq "$HTTP_STATUS" 403
S="$(eligibility_status "$OPERATOR_JAR" "$TENANT_A")"
check S7 "the refused request changed nothing" "DUE or OVERDUE" "$S" is_due "$S"

echo "--- Cross-tenant denial"
http GET "/api/follow-up-queue?limit=1" "$OPERATOR_JAR" -H "X-Tenant-Id: $TENANT_B"
check S8 "operator selecting a tenant it is not a member of is refused" "403" "$(trace)" eq "$HTTP_STATUS" 403
S="$(eligibility_status "$OWNER_JAR" "$TENANT_B")"
check S9 "owner of both tenants cannot read tenant A's customer under tenant B" "status-404" "$S" eq "$S" status-404
dismiss "$OWNER_JAR" "$OWNER_CSRF" "$TENANT_B" "$ETAG" "qa-journey-cross-$RUN_ID"
check S10 "owner cannot dismiss tenant A's customer under tenant B" "404" "$(trace)" eq "$HTTP_STATUS" 404
S="$(queue_contains "$OWNER_JAR" "$TENANT_B" "$CUSTOMER_ID")"
check S11 "tenant B's queue does not contain tenant A's customer" "false" "$S" eq "$S" false

echo "--- Concurrency preconditions"
dismiss "$OPERATOR_JAR" "$OPERATOR_CSRF" "$TENANT_A" "" "qa-journey-noetag-$RUN_ID"
check S12 "dismissal without If-Match is rejected" "400" "$(trace)" eq "$HTTP_STATUS" 400
dismiss "$OPERATOR_JAR" "$OPERATOR_CSRF" "$TENANT_A" "$OLD_POLICY_ETAG" "qa-journey-stale-$RUN_ID"
check S13 "dismissal with a superseded If-Match is rejected" "409" "$(trace)" eq "$HTTP_STATUS" 409
S="$(eligibility_status "$OPERATOR_JAR" "$TENANT_A")"
check S14 "the rejected requests changed nothing" "DUE or OVERDUE" "$S" is_due "$S"

echo "--- Disposition and idempotent replay"
KEY="qa-journey-dismiss-$RUN_ID"
dismiss "$OPERATOR_JAR" "$OPERATOR_CSRF" "$TENANT_A" "$ETAG" "$KEY"
FIRST_STATUS="$HTTP_STATUS"; FIRST_ID="$(body .id)"; FIRST_CUSTOMER="$(body .customerId)"
check S15 "operator dismisses the due follow-up with the current If-Match" "201 for this customer" \
  "$(trace), same customer: $([ "$FIRST_CUSTOMER" = "$CUSTOMER_ID" ] && echo true || echo false)" \
  eq "$FIRST_STATUS:$FIRST_CUSTOMER" "201:$CUSTOMER_ID"
dismiss "$OPERATOR_JAR" "$OPERATOR_CSRF" "$TENANT_A" "$ETAG" "$KEY"
REPLAY_ID="$(body .id)"
check S16 "replaying the same request returns the original dismissal" "200, same dismissal id" \
  "$(trace), ids present: $(is_id "$FIRST_ID" && is_id "$REPLAY_ID" && echo true || echo false), same id: $([ "$REPLAY_ID" = "$FIRST_ID" ] && echo true || echo false)" \
  same_dismissal "$HTTP_STATUS" "$FIRST_ID" "$REPLAY_ID"

echo "--- API sync after the disposition"
S="$(eligibility_status "$OPERATOR_JAR" "$TENANT_A")"
check S17 "eligibility is no longer due" "NOT_YET_DUE or INELIGIBLE" "$S" not_due "$S"
S="$(queue_contains "$OPERATOR_JAR" "$TENANT_A" "$CUSTOMER_ID")"
check S18 "the customer left the operator's due queue" "false" "$S" eq "$S" false
S="$(queue_contains "$VIEWER_JAR" "$TENANT_A" "$CUSTOMER_ID")"
check S19 "the customer left the viewer's due queue" "false" "$S" eq "$S" false

echo "--- No outbound side effect"
# Positive control: the same session and tenant can see the customer, so an empty list is not "customer not visible".
http GET "/api/customers/$CUSTOMER_ID" "$OPERATOR_JAR" "${A[@]}"
VISIBLE="$HTTP_STATUS"
http GET "/api/messages?customerId=$CUSTOMER_ID" "$OPERATOR_JAR" "${A[@]}"
OUTBOUND_COUNT="$( [ "$HTTP_STATUS" = "200" ] && message_count "$WORK_DIR/body" || echo unknown)"
check S20 "no outbound message exists for the fixture customer (visible to the same query)" "customer 200, 0 messages" \
  "customer $VISIBLE, $OUTBOUND_COUNT messages ($(trace))" eq "$VISIBLE:$OUTBOUND_COUNT" "200:0"

record_step S21 "the follow-up queue screen reflects the dismissal after reload" \
  "customer absent from the queue page" "browser step outside this script; see the journey file" not-run manual

# ------------------------------------------------------------------------------
# Cleanup and result
# ------------------------------------------------------------------------------
archive_fixture
printf 'Cleanup: %s\n' "$CLEANUP_NOTE"
write_record || exit 2
printf 'Result: %s passed, %s failed, %s not-run\n' "$STEPS_PASSED" "$STEPS_FAILED" "$STEPS_NOT_RUN"
[ "$STEPS_FAILED" -eq 0 ]
