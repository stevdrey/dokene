#!/usr/bin/env bash
# Retest of #246, part 2: a VALID write (api helper: CSRF, Idempotency-Key, tenant) during the outage and right after recovery; /api/session behaviour.
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa123/s/base.sh; DCP="docker compose"
waith() { for i in $(seq 1 90); do case "$($DCP ps $1 --format '{{.Status}}' 2>/dev/null)" in *healthy*) return 0;; esac; sleep 2; done; return 1; }
qa_login testoperator; TENANT=$A
mk() { jq -nc --arg n "$1 $RUN" --arg p "$(rnd_phone)" '{displayName:$n,phones:[{number:$p,region:"CL",primary:true}]}'; }
$DCP stop postgres >/dev/null 2>&1; sleep 1
t0=$(date +%s%N); api testoperator POST /api/customers "$(mk Ret246-outage)"; ms=$(( ($(date +%s%N) - t0) / 1000000 )); echo "valid POST during the outage -> $CODE after ${ms} ms body='${BODY:0:80}'"
chk RES-09 "valid write during the outage: 503 within 8 s" "503 yes" "$CODE $([ $ms -lt 8000 ] && echo yes || echo no)"
$DCP start postgres >/dev/null 2>&1; waith postgres; sleep 2
for i in 1 2 3 4 5 6; do api testoperator POST /api/customers "$(mk Ret246-after)"; [ "$CODE" = 201 ] && break; sleep 2; done
echo "valid POST after recovery -> $CODE (attempt $i)"; chk RES-09 "write works after recovery with the same session" 201 "$CODE"
chk RES-09 "no row from the failed write" 0 "$(psqlq "select count(*) from dokene.customers where display_name='Ret246-outage $RUN'")"
echo "PASS=$PASSN FAIL=$FAILN"
