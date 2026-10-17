#!/usr/bin/env bash
# QA suite #121: SEC-03 tenant isolation / IDOR. Needs the fixtures of sec121-a.sh ($OUTD/ids).
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa121/s/env121.sh; OUTD=/home/stevdrey/qa121/out; read A B C CA CA2 CB CC PA PA2 PB UO UV UU < $OUTD/ids; TENANT=$A; : > $STUB/fail
cnt() { psqlq "select count(*) from dokene.$1"; }
leak() { echo "$BODY" | grep -Eq "SEC B Caf|Caf. de grano 1 kg B" && echo LEAK || echo none; }
TENANT=$B; PHB=$(getj testuser /api/customers/$CB | jq -r '.phones[0].id'); TENANT=$A
b=$(ai_rows); n0=$(wc -l < $STUB/requests.log); c0=$(cnt customers); p0=$(cnt purchases)
tst() { local lab=$1 m=$2 p=$3 d=$4; shift 4; api testoperator "$m" "$p" "$d" "$@"; chk SEC-03 "$lab -> 404" 404 "$CODE"; chk SEC-03 "$lab: no B content in body" none "$(leak)"; }
echo "== operator (OPERATOR in A) under tenant A header using tenant B identifiers"
tst "GET B customer" GET /api/customers/$CB ''
tst "PUT B customer" PUT /api/customers/$CB '{"displayName":"hijack","phones":[]}' -H 'If-Match: "0"'
api testuser DELETE /api/customers/$CB '' -H 'If-Match: "0"'; chk SEC-03 "DELETE B customer under A by OWNER of A -> 404" 404 "$CODE"
tst "contact-policy of B customer" GET /api/customers/$CB/contact-policy ''
tst "contact-eligibility of B customer" GET "/api/customers/$CB/contact-eligibility?channel=WHATSAPP&contactId=$PHB" ''
tst "consent grant on B customer's phone" PUT /api/customers/$CB/contacts/$PHB/consents/WHATSAPP '{"status":"GRANTED","source":"CUSTOMER_VERBAL"}' -H 'If-Match: "0"'
tst "do-not-contact on B customer" PUT /api/customers/$CB/do-not-contact '{"enabled":true,"source":"CUSTOMER_WRITTEN"}' -H 'If-Match: "0"'
tst "list purchases of B customer" GET /api/customers/$CB/purchases ''
tst "read B purchase via B customer" GET /api/customers/$CB/purchases/$PB ''
tst "create purchase on B customer" POST /api/customers/$CB/purchases '{"purchasedAt":"2026-08-03T14:00:00Z","description":"inject"}' -H "Idempotency-Key: sec121-idor-$RUN"
tst "read B purchase via A customer id" GET /api/customers/$CA/purchases/$PB ''
tst "purchase history of B purchase via A customer" GET /api/customers/$CA/purchases/$PB/history ''
tst "A customer + A2's purchase id (same tenant, wrong customer)" GET /api/customers/$CA/purchases/$PA2 ''
tst "update B purchase via A customer" PUT /api/customers/$CA/purchases/$PB '{"purchasedAt":"2026-08-03T14:00:00Z","description":"hijack"}' -H 'If-Match: "0"'
tst "void B purchase" DELETE /api/customers/$CB/purchases/$PB '' -H 'If-Match: "0"'
tst "follow-up-policy of B customer" GET /api/customers/$CB/follow-up-policy ''
tst "follow-up-eligibility of B customer" GET /api/customers/$CB/follow-up-eligibility ''
tst "PUT follow-up-policy of B customer" PUT /api/customers/$CB/follow-up-policy '{"cadenceDays":5}' -H 'If-Match: "0"'
tst "manual follow-up on B customer" POST /api/customers/$CB/manual-follow-ups '{"notes":"x"}' -H 'If-Match: "0"' -H "Idempotency-Key: sec121-m-$RUN"
tst "dismissal on B customer" POST /api/customers/$CB/follow-up-dismissals '{"notes":"x"}' -H 'If-Match: "0"' -H "Idempotency-Key: sec121-d-$RUN"
tst "snooze on B customer" PUT /api/customers/$CB/follow-up-snooze '{"until":"2030-01-01"}' -H 'If-Match: "0"'
tst "RECOMMENDATION for B customer under A" POST /api/customers/$CB/recommendation '' -H 'If-Match: "0"'
tst "DRAFT for B customer under A" POST /api/customers/$CB/draft '{"action":"GENERAL_CHECK_IN","templateIntent":"GENERAL_FOLLOW_UP"}' -H 'If-Match: "0"'
chk SEC-03 "cross-tenant AI calls: no AI audit row and no provider request" "0 0" "$(( $(ai_rows) - b )) $(( $(wc -l < $STUB/requests.log) - n0 ))"
chk SEC-03 "no customer was created/changed by the writes" "$c0" "$(cnt customers)"; chk SEC-03 "no purchase was created by the writes" "$p0" "$(cnt purchases)"
chk SEC-03 "B customer unchanged" "ACTIVE SEC B Café QA" "$(psqlq "select status||' '||display_name from dokene.customers where id='$CB'")"
api testoperator GET /api/customers ''; chk SEC-03 "customer list under A contains no B customer" 0 "$(echo "$BODY"|jq "[.customers[]|select(.id==\"$CB\")]|length")"
api testoperator GET /api/follow-up-queue ''; chk SEC-03 "A queue contains no B customer" 0 "$(echo "$BODY"|jq "[.items[]|select(.customerId==\"$CB\")]|length")"
echo "== workspace header replacement (X-Tenant-Id)"
TENANT=$B; api testoperator GET /api/customers/$CA ''; chk SEC-03 "A customer id under tenant B header -> 404" 404 "$CODE"
api testoperator GET /api/customers/$CB ''; chk SEC-03 "B customer under B header (legit control) -> 200" 200 "$CODE"
TENANT=$C; api testviewer GET /api/customers/$CC ''; chk SEC-03 "tenant C header by revoked member -> 403" 403 "$CODE"; V1=$CODE
TENANT=00000000-0000-0000-0000-000000000099; api testoperator GET /api/customers ''; chk SEC-03 "unknown tenant id -> 403" 403 "$CODE"; chk SEC-03 "revoked-member vs unknown tenant indistinguishable (no existence oracle)" "$V1" "$CODE"
TENANT=not-a-uuid; api testoperator GET /api/customers ''; chk SEC-03 "malformed tenant header -> 400" 400 "$CODE"
TENANT=; api testoperator GET /api/customers ''; chk SEC-03 "missing tenant header -> 403" 403 "$CODE"
chk SEC-03 "tenant C detail by revoked member denied" 403 "$(curl -s -o /dev/null -w '%{http_code}' -b $QA_TMP/jar-testviewer.txt $BFF/api/tenants/$C)"
TENANT=$A; api testoperator GET /api/memberships ''; chk SEC-03 "membership list under A has only A memberships" "$A" "$(echo "$BODY"|jq -r '[.[].tenantId]|unique|join(",")')"
echo "== cache bleed: same URL, tenant switch in one session"
TENANT=$A; api testoperator GET /api/customers ''; N_A=$(echo "$BODY"|jq '.customers|length'); H1="$HDRS"; TENANT=$B; api testoperator GET /api/customers ''; N_B=$(echo "$BODY"|jq '.customers|length')
echo "  A=$N_A customers, B=$N_B customers"; chk SEC-03 "A and B lists differ" yes "$([ "$N_A" != "$N_B" ] && echo yes || echo no)"; chk SEC-03 "customer list is Cache-Control no-store" yes "$(echo "$H1"|grep -qi '^cache-control:.*no-store' && echo yes || echo no)"
echo "PASS=$PASSN FAIL=$FAILN"
