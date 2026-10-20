#!/usr/bin/env bash
# Retest of #246 (RES-09): API behaviour while PostgreSQL is stopped, on the local Compose stack built from the branch.
cd "${DOKENE_ROOT:?set DOKENE_ROOT to the repository root}"
export QA_TMP=${QA_TMP:-$(mktemp -d)}
set -a; . ./.env; set +a; . "${QA_HELPERS:?set QA_HELPERS to the local qa.sh harness (login/api/chk/psqlq)}"
DCP="docker compose"
leak() { echo "$1" | grep -Eic 'jdbc|postgres|password|dokene_runtime|org\.postgresql|SQLException|Exception|HikariPool|Connection is not available|\bat io\.github|stack'; }
waith() { for i in $(seq 1 90); do case "$($DCP ps $1 --format '{{.Status}}' 2>/dev/null)" in *healthy*) return 0;; esac; sleep 2; done; return 1; }
NAME="RES09 retest $(date +%s)"
qa_login testoperator; A=$(curl -s -b $QA_TMP/jar-testoperator.txt $BFF/api/tenants | jq -r '.[0].tenantId'); TENANT=$A
JAR=$QA_TMP/jar-testoperator.txt
CSRF=$(curl -s -b $JAR -c $JAR $BFF/api/session | jq -r .csrfToken)

c=$(curl -s -o /dev/null -w '%{http_code}' -b $JAR -H "X-Tenant-Id: $A" $BFF/api/customers)
chk BASE "GET /api/customers before the outage" 200 "$c"

SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
$DCP stop postgres >/dev/null 2>&1; echo "postgres stopped at $SINCE"

call() { # call <label> <curl args...>
  local label=$1; shift; : > $QA_TMP/rb; : > $QA_TMP/rh
  local t0=$(date +%s%N)
  local code=$(curl -s -D $QA_TMP/rh -o $QA_TMP/rb -w '%{http_code}' --max-time 90 -b $JAR -H "X-Tenant-Id: $A" "$@")
  local ms=$(( ($(date +%s%N) - t0) / 1000000 ))
  local ra=$(grep -i '^retry-after:' $QA_TMP/rh | tr -d '\r' | awk '{print $2}')
  local rid=$(grep -ic '^x-request-id:' $QA_TMP/rh)
  local len=$(wc -c < $QA_TMP/rb)
  echo "$label -> http=$code after ${ms} ms, Retry-After='$ra', X-Request-Id present=$rid, body bytes=$len"
  chk RES-09 "$label: status 503" 503 "$code"
  chk RES-09 "$label: Retry-After 5" 5 "$ra"
  chk RES-09 "$label: correlation header kept" 1 "$rid"
  chk RES-09 "$label: empty body, no leak" "0 0" "$len $(leak "$(cat $QA_TMP/rb)")"
  chk RES-09 "$label: fails fast (< 5000 ms)" yes "$([ $ms -lt 5000 ] && echo yes || echo "${ms}ms")"
}
call "GET /api/customers (1st)" $BFF/api/customers
call "GET /api/customers (2nd)" $BFF/api/customers
call "GET /api/follow-up-queue" $BFF/api/follow-up-queue
call "POST /api/customers" -X POST -H "X-CSRF-TOKEN: $CSRF" -H 'Content-Type: application/json' \
  -d "{\"displayName\":\"$NAME\",\"phones\":[{\"number\":\"8888 7777\",\"region\":\"CR\",\"primary\":true}]}" $BFF/api/customers

chk RES-09 "backend container still running during the outage" running "$(docker inspect -f '{{.State.Status}}' dokene-backend-1)"
LOGS=$($DCP logs --no-log-prefix --since "$SINCE" backend 2>&1)
echo "--- backend log lines during the outage (WARN/ERROR) ---"
echo "$LOGS" | grep -E ' (WARN|ERROR) ' | sed -E 's/^[^ ]+ +//' | cut -c1-220
chk RES-09 "outage logged as 'Database unavailable' per request" 4 "$(echo "$LOGS" | grep -c 'Database unavailable at the HTTP boundary')"
chk RES-09 "no SQL/host/credential/exception text in backend log" 0 "$(echo "$LOGS" | grep -Eic 'jdbc:|password|dokene_runtime|Connection is not available|Connection to postgres|refused|PSQLException.*:|^\s+at ')"
chk RES-09 "no stray 500 in backend log" 0 "$(echo "$LOGS" | grep -Eic 'Servlet.service\(\).*threw exception|Internal Server Error')"

$DCP start postgres >/dev/null 2>&1; waith postgres; sleep 4
t0=$(date +%s%N); c=$(curl -s -o /dev/null -w '%{http_code}' -b $JAR -H "X-Tenant-Id: $A" $BFF/api/customers)
chk RES-09 "recovered without a backend restart (same session)" 200 "$c"; echo "  first call after recovery: $(( ($(date +%s%N) - t0) / 1000000 )) ms"
chk RES-09 "the refused POST left no row" 0 "$(psqlq "select count(*) from dokene.customers where display_name='$NAME'")"
echo "PASS=$PASSN FAIL=$FAILN"
