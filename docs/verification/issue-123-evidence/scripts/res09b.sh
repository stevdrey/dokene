#!/usr/bin/env bash
# QA suite #123 RES-09 B2 (re-run with long timeouts): latency and sanitization of API failures while PostgreSQL is stopped.
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa123/s/base.sh; DCP="docker compose"
leak() { echo "$1" | grep -Eic 'jdbc|postgres|password|dokene_runtime|org\.postgresql|SQLException|Exception|\bat io\.github|stack'; }
waith() { for i in $(seq 1 90); do case "$($DCP ps $1 --format '{{.Status}}' 2>/dev/null)" in *healthy*) return 0;; esac; sleep 2; done; return 1; }
qa_login testoperator; TENANT=$A; $DCP stop postgres >/dev/null 2>&1
for ep in /api/customers /api/follow-up-queue; do : > $QA_TMP/rb; t0=$(date +%s%N); c=$(curl -s -o $QA_TMP/rb -w '%{http_code}' --max-time 90 -b $QA_TMP/jar-testoperator.txt -H "X-Tenant-Id: $A" $BFF$ep); ms=$(( ($(date +%s%N) - t0) / 1000000 )); echo "GET $ep during the outage -> http=$c after ${ms} ms body='$(head -c 100 $QA_TMP/rb | tr -d '\n')'"; chk RES-09 "GET $ep: terminal 5xx, sanitized" "yes 0" "$([ "$c" -ge 500 ] && echo yes || echo $c) $(leak "$(cat $QA_TMP/rb)")"; echo "  latency of the failure: ${ms} ms"; done
t0=$(date +%s%N); api testoperator POST /api/customers '{"displayName":"x"}'; echo "POST during the outage -> $CODE after $(( ($(date +%s%N) - t0) / 1000000 )) ms"
$DCP start postgres >/dev/null 2>&1; waith postgres; sleep 6; c=$(curl -s -o /dev/null -w '%{http_code}' -b $QA_TMP/jar-testoperator.txt -H "X-Tenant-Id: $A" $BFF/api/customers); chk RES-09 "recovered without a backend restart" 200 "$c"
echo "PASS=$PASSN FAIL=$FAILN"
