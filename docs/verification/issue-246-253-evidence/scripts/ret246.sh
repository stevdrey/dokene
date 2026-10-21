#!/usr/bin/env bash
# Retest of #246 (RES-09 B2) on main @ 6689d27: latency, status, Retry-After and body of API failures while PostgreSQL is stopped; recovery without a backend restart.
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa123/s/base.sh; DCP="docker compose"
leak() { echo "$1" | grep -Eic 'jdbc|postgres|password|dokene_runtime|org\.postgresql|SQLException|Exception|\bat io\.github|stack'; }
waith() { for i in $(seq 1 90); do case "$($DCP ps $1 --format '{{.Status}}' 2>/dev/null)" in *healthy*) return 0;; esac; sleep 2; done; return 1; }
qa_login testoperator; TENANT=$A
hit() { # path [method] [body] -> H_CODE H_MS H_RETRY H_BODY
  : > $QA_TMP/rb; local t0=$(date +%s%N); local m=${2:-GET}
  H_CODE=$(curl -s -D $QA_TMP/rh -o $QA_TMP/rb -w '%{http_code}' --max-time 90 -X $m -b $QA_TMP/jar-testoperator.txt -H "X-Tenant-Id: $A" ${3:+-H 'Content-Type: application/json' -d "$3"} ${CSRF:+-H "X-CSRF-TOKEN: $CSRF"} $BFF$1)
  H_MS=$(( ($(date +%s%N) - t0) / 1000000 )); H_RETRY=$(grep -i '^retry-after:' $QA_TMP/rh | tr -d '\r' | cut -d' ' -f2); H_BODY=$(cat $QA_TMP/rb); }
CSRF=$(curl -s -b $QA_TMP/jar-testoperator.txt $BFF/api/session | jq -r .csrfToken)
chk RES-09 "pre: GET /api/customers works" 200 "$(curl -s -o /dev/null -w '%{http_code}' -b $QA_TMP/jar-testoperator.txt -H "X-Tenant-Id: $A" $BFF/api/customers)"
$DCP stop postgres >/dev/null 2>&1; sleep 1
for ep in /api/customers /api/follow-up-queue /api/session; do
  hit $ep; echo "GET $ep during the outage -> http=$H_CODE after ${H_MS} ms Retry-After='$H_RETRY' body_len=${#H_BODY}"
  chk RES-09 "GET $ep: 503" 503 "$H_CODE"; chk RES-09 "GET $ep: Retry-After present" yes "$([ -n "$H_RETRY" ] && echo yes || echo no)"
  chk RES-09 "GET $ep: fails fast (< 8000 ms)" yes "$([ $H_MS -lt 8000 ] && echo yes || echo "no ${H_MS}")"; chk RES-09 "GET $ep: empty/sanitized body" 0 "$(leak "$H_BODY")"; done
hit /api/customers POST '{"displayName":"Ret246 outage write","phones":[{"number":"+56911112222","region":"CL","primary":true}]}'
echo "POST /api/customers during the outage -> http=$H_CODE after ${H_MS} ms Retry-After='$H_RETRY' body='$H_BODY'"
chk RES-09 "POST during the outage: 503 + Retry-After, fast, sanitized" "503 yes yes 0" "$H_CODE $([ -n "$H_RETRY" ] && echo yes || echo no) $([ $H_MS -lt 8000 ] && echo yes || echo no) $(leak "$H_BODY")"
hit /api/customers/$(cat /home/stevdrey/qa123/out/baseline.id)/recommendation POST '{}'
echo "AI recommendation during the outage -> http=$H_CODE after ${H_MS} ms Retry-After='$H_RETRY'"
chk RES-09 "AI call during the outage: 503/5xx, fast, no leak" "yes yes 0" "$([ $H_CODE -ge 500 ] && echo yes || echo $H_CODE) $([ $H_MS -lt 8000 ] && echo yes || echo no) $(leak "$H_BODY")"
LOGS=$($DCP logs --no-log-prefix backend 2>&1 | tail -400)
echo "log lines 'Database unavailable at the HTTP boundary': $(echo "$LOGS" | grep -c 'Database unavailable at the HTTP boundary')"; echo "$LOGS" | grep 'Database unavailable at the HTTP boundary' | head -2 | cut -c1-240
chk RES-09 "log has class-only outage line" yes "$([ $(echo "$LOGS" | grep -c 'Database unavailable at the HTTP boundary') -ge 1 ] && echo yes || echo no)"
$DCP start postgres >/dev/null 2>&1; waith postgres; for i in 1 2 3 4 5 6 7 8; do c=$(curl -s -o /dev/null -w '%{http_code}' -b $QA_TMP/jar-testoperator.txt -H "X-Tenant-Id: $A" $BFF/api/customers); [ "$c" = 200 ] && break; sleep 2; done
echo "recovery: GET /api/customers -> $c (attempts $i)"; chk RES-09 "recovered without a backend restart" 200 "$c"
hit /api/customers POST '{"displayName":"Ret246 after recovery","phones":[{"number":"+56911113333","region":"CL","primary":true}]}'; chk RES-09 "writes work after recovery" 201 "$H_CODE"
echo "backend restarts: $($DCP ps backend --format '{{.Status}}')"
echo "PASS=$PASSN FAIL=$FAILN"
