#!/usr/bin/env bash
# QA suite #121: fixtures (tenants B and C), SEC-01 authn/CSRF/origin/cookies, SEC-02 authorization by role. Writes ids to $OUTD/ids.
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa121/s/env121.sh; OUTD=/home/stevdrey/qa121/out; OUT=$QA_TMP; : > $STUB/fail; echo 0 > $STUB/delay.ms
raw() { local jar=$1 m=$2 p=$3; shift 3; RC=$(curl -s -o $OUT/raw.body -w '%{http_code}' -b $jar -X $m "$BFF$p" "$@"); }
NOJAR=$QA_TMP/none.txt; : > $NOJAR
echo "tenant A=$A testuser=$UU operator=$UO viewer=$UV"
CA=$(mk "SEC A Café QA $RUN" "$(rnd_phone)" ""); consent $CA GRANTED >/dev/null; buy $CA 2026-08-01T14:00:00Z "Café de grano 1 kg" >/dev/null
mkt() { local tok=$(curl -s -b $QA_TMP/jar-testuser.txt $BFF/api/session|jq -r .csrfToken); curl -s -b $QA_TMP/jar-testuser.txt -X POST $BFF/api/tenants -H "X-CSRF-TOKEN: $tok" -H 'Content-Type: application/json' -H "Idempotency-Key: sec121-$RUN-$1" -d "{\"displayName\":\"SEC121 $1 $RUN\"}"|jq -r .tenantId; }
mkc() { api testuser POST /api/customers "$(jq -nc --arg n "$1" --arg p "$(rnd_phone)" '{displayName:$n,phones:[{number:$p,region:"CL",primary:true}]}')"; echo "$BODY"|jq -r .id; }
cons() { local pid=$(getj $1 /api/customers/$2 | jq -r '.phones[0].id') v=$(getj $1 /api/customers/$2/contact-policy | jq .version); api $1 PUT /api/customers/$2/contacts/$pid/consents/WHATSAPP '{"status":"GRANTED","source":"CUSTOMER_VERBAL"}' -H "If-Match: \"$v\""; }
buyu() { api $1 POST /api/customers/$2/purchases "{\"purchasedAt\":\"$3\",\"description\":\"$4\"}" -H "Idempotency-Key: sec121-$RUN-$RANDOM"; PID=$(echo "$BODY"|jq -r .id); }
B=$(mkt B); C=$(mkt C); echo "tenant B=$B tenant C=$C"
TENANT=$B; api testuser POST /api/memberships "{\"identityId\":\"$UO\",\"role\":\"ADMIN\"}"; chk FIX "operator added to B as ADMIN" 201 "$CODE"
api testuser POST /api/memberships "{\"identityId\":\"$UV\",\"role\":\"VIEWER\"}"; chk FIX "viewer added to B as VIEWER" 201 "$CODE"
CB=$(mkc "SEC B Café QA"); cons testuser $CB >/dev/null; buyu testuser $CB 2026-08-01T14:00:00Z "Café de grano 1 kg B"; PB=$PID
TENANT=$C; api testuser POST /api/memberships "{\"identityId\":\"$UV\",\"role\":\"VIEWER\"}"; chk FIX "viewer added to C" 201 "$CODE"
api testuser POST /api/memberships "{\"identityId\":\"$UO\",\"role\":\"OPERATOR\"}"; chk FIX "operator added to C" 201 "$CODE"
CC=$(mkc "SEC C Café QA"); cons testuser $CC >/dev/null; buyu testuser $CC 2026-08-01T14:00:00Z "Café C"
api testuser DELETE /api/memberships/$UV ''; chk FIX "viewer membership in C revoked" 204 "$CODE"
TENANT=$A; CA2=$(mk "SEC A2 Té QA $RUN" "$(rnd_phone)" ""); buy $CA2 2026-08-02T14:00:00Z "Té verde 500 g" >/dev/null; PA2=$(psqlq "select id from dokene.purchases where customer_id='$CA2' limit 1"); PA=$(psqlq "select id from dokene.purchases where customer_id='$CA' limit 1")
echo "$A $B $C $CA $CA2 $CB $CC $PA $PA2 $PB $UO $UV $UU" > $OUTD/ids
echo "== SEC-01 authentication / CSRF / origin"
for ep in "GET /api/customers" "GET /api/session" "GET /api/tenants" "GET /api/follow-up-queue"; do set -- $ep; raw $NOJAR $1 $2 -H "X-Tenant-Id: $A"; chk SEC-01 "no session $ep -> 401" 401 "$RC"; done
for ep in "POST /api/customers/$CA/recommendation" "POST /api/customers/$CA/draft" "POST /api/customers" "PUT /api/follow-up-policy" "DELETE /api/customers/$CA"; do set -- $ep; raw $NOJAR $1 $2 -H "X-Tenant-Id: $A" -H 'Content-Type: application/json' -d '{}'; chk SEC-01 "no session $ep -> 401" 401 "$RC"; done
J=$QA_TMP/jar-testoperator.txt; TOK=$(curl -s -b $J $BFF/api/session | jq -r .csrfToken); b=$(ai_rows); n0=$(wc -l < $STUB/requests.log)
for ep in "POST /api/customers/$CA/recommendation" "POST /api/customers/$CA/draft" "POST /api/customers" "PUT /api/follow-up-policy" "POST /api/customers/$CA/manual-follow-ups"; do set -- $ep
  raw $J $1 $2 -H "X-Tenant-Id: $A" -H 'Content-Type: application/json' -d '{}'; chk SEC-01 "session, absent CSRF $ep -> 403" 403 "$RC"
  raw $J $1 $2 -H "X-Tenant-Id: $A" -H 'X-CSRF-TOKEN: not-a-valid-token' -H 'Content-Type: application/json' -d '{}'; chk SEC-01 "session, invalid CSRF $ep -> 403" 403 "$RC"; done
chk SEC-01 "CSRF-rejected AI POSTs: no AI audit row and no provider request" "0 0" "$(( $(ai_rows) - b )) $(( $(wc -l < $STUB/requests.log) - n0 ))"
raw $J POST /api/customers/$CA/recommendation -H "X-Tenant-Id: $A" -H "X-CSRF-TOKEN: $TOK" -H 'Origin: http://evil.test' -H 'Content-Type: application/json'; chk SEC-01 "valid CSRF but unapproved Origin http://evil.test -> 403" 403 "$RC"
raw $J POST /api/customers -H "X-Tenant-Id: $A" -H "X-CSRF-TOKEN: $TOK" -H 'Origin: http://evil.test' -H 'Content-Type: application/json' -d '{"displayName":"EVIL-ORIGIN-CANARY"}'; chk SEC-01 "business mutation with unapproved Origin -> 403" 403 "$RC"
chk SEC-01 "unapproved-Origin POST did not create a customer" 0 "$(psqlq "select count(*) from dokene.customers where display_name='EVIL-ORIGIN-CANARY'")"
PF=$(curl -s -D - -o /dev/null -X OPTIONS "$BFF/api/customers/$CA/recommendation" -H 'Origin: http://evil.test' -H 'Access-Control-Request-Method: POST' -H 'Access-Control-Request-Headers: x-csrf-token,x-tenant-id'); echo "  preflight: $(echo "$PF"|head -1)"
chk SEC-01 "CORS preflight from unapproved origin gets no Allow-Origin" "" "$(echo "$PF"|grep -i '^access-control-allow-origin'|tr -d '\r')"
rm -f $QA_TMP/ck.txt; curl -s -c $QA_TMP/ck.txt -D $QA_TMP/h1 -o /dev/null $BFF/oauth2/authorization/dokene; l=$(grep -i '^location:' $QA_TMP/h1|tr -d '\r\n'|awk '{print $2}')
curl -s -c $QA_TMP/ck.txt -b $QA_TMP/ck.txt -o $QA_TMP/b.tmp "$l"; a=$(grep -o 'action="[^"]*"' $QA_TMP/b.tmp|head -1|cut -d'"' -f2|sed 's/&amp;/\&/g')
curl -s -c $QA_TMP/ck.txt -b $QA_TMP/ck.txt -D $QA_TMP/h2 -o /dev/null --data-urlencode username=testviewer --data-urlencode password=$PW "$a"; l=$(grep -i '^location:' $QA_TMP/h2|tr -d '\r\n'|awk '{print $2}')
PRE=$(grep -i '^set-cookie: *JSESSIONID' $QA_TMP/h1 | sed -n 's/.*JSESSIONID=\([^;]*\).*/\1/p'); curl -s -c $QA_TMP/ck.txt -b $QA_TMP/ck.txt -D $QA_TMP/h3 -o /dev/null "$l"
SC=$(grep -i '^set-cookie: *JSESSIONID' $QA_TMP/h3 | tr -d '\r'); POST=$(echo "$SC" | sed -n 's/.*JSESSIONID=\([^;]*\).*/\1/p'); echo "  Set-Cookie after login: $(echo "$SC" | sed 's/JSESSIONID=[^;]*/JSESSIONID=<redacted>/')"
chk SEC-01 "session cookie is HttpOnly" yes "$(echo "$SC"|grep -qi httponly && echo yes || echo no)"; chk SEC-01 "session cookie is SameSite=Lax" yes "$(echo "$SC"|grep -qi 'samesite=lax' && echo yes || echo no)"
chk SEC-01 "Secure absent only because of the documented local HTTP override" no "$(echo "$SC"|grep -qi '; *secure' && echo yes || echo no)"
chk SEC-01 "session id rotated at login (fixation): pre-login id differs from post-login id" yes "$([ -n "$POST" ] && [ "$PRE" != "$POST" ] && echo yes || echo "pre=${PRE:0:4} post=${POST:0:4}")"
J2=$QA_TMP/jar-lo.txt; cp $QA_TMP/jar-testoperator.txt $J2; T2=$(curl -s -b $J2 $BFF/api/session | jq -r .csrfToken)
raw $J2 POST /logout -H "X-CSRF-TOKEN: $T2"; echo "  logout rc=$RC"
raw $J2 GET /api/customers -H "X-Tenant-Id: $A"; chk SEC-01 "logged-out session cookie reused on GET -> 401" 401 "$RC"
raw $J2 POST /api/customers/$CA/recommendation -H "X-Tenant-Id: $A" -H "X-CSRF-TOKEN: $T2" -H 'Content-Type: application/json'; chk SEC-01 "logged-out session + old CSRF on AI POST -> 401" 401 "$RC"
raw $NOJAR GET /api/customers -H "X-Tenant-Id: $A" -H 'Cookie: JSESSIONID=ABCDEF0123456789'; chk SEC-01 "forged JSESSIONID -> 401" 401 "$RC"
qa_login testoperator
echo "== SEC-02 authorization by role (direct calls)"
role_ai() { TENANT=$2; local cust=$3 b=$(ai_rows) n0=$(wc -l < $STUB/requests.log); sleep 3.2
  api $1 GET /api/customers/$cust/follow-up-policy ''; local v=$(etag); api $1 POST /api/customers/$cust/recommendation '' -H "If-Match: \"$v\""; R_REC=$CODE
  sleep 3.2; api $1 POST /api/customers/$cust/draft '{"action":"GENERAL_CHECK_IN","templateIntent":"GENERAL_FOLLOW_UP"}' -H "If-Match: \"$v\""; R_DRAFT=$CODE; R_AUD=$(( $(ai_rows) - b )); R_PROV=$(( $(wc -l < $STUB/requests.log) - n0 )); echo "  [$1@${2:0:8}] recommendation=$R_REC draft=$R_DRAFT audit+$R_AUD provider requests=$R_PROV"; }
role_ai testviewer $A $CA; chk SEC-02 "VIEWER recommendation/draft denied" "403 403" "$R_REC $R_DRAFT"; chk SEC-02 "VIEWER denials: no AI audit row, no provider request" "0 0" "$R_AUD $R_PROV"
TENANT=$A; api testviewer POST /api/customers '{"displayName":"v"}'; chk SEC-02 "VIEWER create customer denied" 403 "$CODE"; api testviewer PUT /api/follow-up-policy '{}'; chk SEC-02 "VIEWER update tenant policy denied" 403 "$CODE"
api testviewer POST /api/customers/$CA/manual-follow-ups '{}'; chk SEC-02 "VIEWER manual follow-up denied" 403 "$CODE"; api testviewer POST /api/memberships "{\"identityId\":\"$UU\",\"role\":\"OWNER\"}"; chk SEC-02 "VIEWER add membership denied" 403 "$CODE"
role_ai testoperator $A $CA; chk SEC-02 "OPERATOR recommendation/draft allowed" "200 200" "$R_REC $R_DRAFT"
TENANT=$A; api testoperator PUT /api/follow-up-policy '{}'; chk SEC-02 "OPERATOR update tenant policy denied (TENANT_UPDATE)" 403 "$CODE"; api testoperator POST /api/memberships "{\"identityId\":\"$UV\",\"role\":\"ADMIN\"}"; chk SEC-02 "OPERATOR add membership denied" 403 "$CODE"
api testoperator DELETE /api/customers/$CA2 '' -H 'If-Match: "0"'; chk SEC-02 "OPERATOR delete customer denied (CUSTOMER_DELETE)" 403 "$CODE"
role_ai testoperator $B $CB; chk SEC-02 "ADMIN (operator in B) recommendation/draft allowed" "200 200" "$R_REC $R_DRAFT"
role_ai testuser $A $CA; chk SEC-02 "OWNER recommendation/draft allowed" "200 200" "$R_REC $R_DRAFT"
role_ai testviewer $B $CB; chk SEC-02 "VIEWER in B denied" "403 403" "$R_REC $R_DRAFT"
role_ai testviewer $C $CC; chk SEC-02 "REVOKED membership denied" "403 403" "$R_REC $R_DRAFT"; chk SEC-02 "REVOKED denials: no AI audit row, no provider request" "0 0" "$R_AUD $R_PROV"
TENANT=$C; api testviewer GET /api/customers ''; chk SEC-02 "REVOKED membership read customers denied" 403 "$CODE"
echo "PASS=$PASSN FAIL=$FAILN"
