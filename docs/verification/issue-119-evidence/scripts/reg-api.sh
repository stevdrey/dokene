#!/usr/bin/env bash
# QA suite #119, API level: REG-01/03/04/05/06 (+ OBS-01 role check). Run from the repository root with the stack up.
set -a; . ./.env; set +a; export QA_TMP=$(mktemp -d); . "$(dirname "$0")/qa.sh"
for u in testuser testoperator testviewer; do qa_login $u; done
# ---------------- REG-01 ----------------
chk REG-01 "flyway: at least 15 migrations applied" "ok" "$([ "$(psqlq 'select count(*) from dokene.flyway_schema_history')" -ge 15 ] && echo ok || echo few)"
chk REG-01 "flyway: every migration successful" "t" "$(psqlq 'select bool_and(success) from dokene.flyway_schema_history')"
chk REG-01 "runtime role is not superuser and does not bypass RLS" "dokene_runtime|f|f" "$(psqlq "select rolname,rolsuper,rolbypassrls from pg_roles where rolname='dokene_runtime'")"
chk REG-01 "migration role is not superuser" "dokene_migration|f|f" "$(psqlq "select rolname,rolsuper,rolbypassrls from pg_roles where rolname='dokene_migration'")"
chk REG-01 "BFF returns 401 without a session" 401 "$(curl -s -o /dev/null -w '%{http_code}' $BFF/api/session)"
NORTE=$(curl -s -b $QA_TMP/jar-testoperator.txt $BFF/api/tenants | jq -r '.[0].tenantId'); TENANT=$NORTE
chk REG-01 "operator is OPERATOR in Norte" OPERATOR "$(curl -s -b $QA_TMP/jar-testoperator.txt $BFF/api/tenants | jq -r '.[0].role')"
chk REG-01 "viewer is VIEWER in Norte" VIEWER "$(curl -s -b $QA_TMP/jar-testviewer.txt $BFF/api/tenants | jq -r '.[0].role')"
api testuser POST /api/tenants '{"displayName":"QA Café Sur","idempotencyKey":"qa-seed-cafe-sur"}' -H "Idempotency-Key: qa-seed-cafe-sur"
SUR=$(echo "$BODY" | jq -r .tenantId); chk REG-01 "second tenant provisioned (owner)" 201 "$CODE"
chk REG-01 "owner is OWNER in both tenants" "2 OWNER" "$(curl -s -b $QA_TMP/jar-testuser.txt $BFF/api/tenants | jq -r '[length, (map(.role)|unique|join(","))]|join(" ")')"
TENANT=$SUR; api testoperator GET /api/customers; chk REG-01 "operator has no access to tenant Sur" 403 "$CODE"; TENANT=$NORTE
# ---------------- REG-03 ----------------
api testoperator POST /api/customers '{"displayName":"Ana Rojas","phones":[{"number":"+56931110001","region":"CL","primary":true}]}'; chk REG-03 "create CL customer" 201 "$CODE"; ANA=$(echo "$BODY"|jq -r .id)
api testoperator POST /api/customers '{"displayName":"Ana CR","phones":[{"number":"8888 1234","region":"CR","primary":true}]}'; chk REG-03 "create CR customer" 201 "$CODE"; chk REG-03 "CR number normalized to E.164" "+50688881234" "$(echo "$BODY"|jq -r '.phones[0].e164')"
api testoperator POST /api/customers '{"displayName":"Intl US","phones":[{"number":"+14155552671","region":"US","primary":true}]}'; chk REG-03 "create international (US) customer" 201 "$CODE"
api testoperator POST /api/customers '{"displayName":"Dup","phones":[{"number":"+56931110001","region":"CL","primary":true}]}'; chk REG-03 "duplicate phone in same tenant" 409 "$CODE"
api testoperator POST /api/customers '{"displayName":"Bad","phones":[{"number":"123","region":"CL","primary":true}]}'; chk REG-03 "invalid CL phone -> 400 field phones[0].number (#72)" "400 phones[0].number" "$CODE $(echo "$BODY"|jq -r .field)"
api testoperator POST /api/customers '{"displayName":"Bad","phones":[{"number":"123","region":"CR","primary":true}]}'; chk REG-03 "invalid CR phone -> 400 field phones[0].number" "400 phones[0].number" "$CODE $(echo "$BODY"|jq -r .field)"
api testoperator POST /api/customers '{"displayName":"NoReg","phones":[{"number":"88881299","primary":true}]}'; chk REG-03 "missing region -> 400 field phones[0].region" "400 phones[0].region" "$CODE $(echo "$BODY"|jq -r .field)"
api testoperator POST /api/customers '{"displayName":"   ","phones":[{"number":"+56931110002","region":"CL","primary":true}]}'; chk REG-03 "blank name -> 400 field displayName (#127)" "400 displayName" "$CODE $(echo "$BODY"|jq -r .field)"
api testoperator POST /api/customers "{\"displayName\":\"$(printf 'x%.0s' {1..161})\",\"phones\":[{\"number\":\"+56931110003\",\"region\":\"CL\",\"primary\":true}]}"; chk REG-03 "161-char name rejected (#127)" "400 displayName" "$CODE $(echo "$BODY"|jq -r .field)"
api testoperator POST /api/customers "{\"displayName\":\"$(printf 'y%.0s' {1..160})\",\"phones\":[{\"number\":\"+56931110004\",\"region\":\"CL\",\"primary\":true}]}"; chk REG-03 "160-char name accepted" 201 "$CODE"
api testoperator PUT /api/customers/$ANA '{"displayName":"Ana Rojas M","phones":[{"number":"+56931110001","region":"CL","primary":true}]}'; chk REG-03 "update without If-Match -> 400 field version" "400 version" "$CODE $(echo "$BODY"|jq -r .field)"
api testoperator PUT /api/customers/$ANA '{"displayName":"Ana Rojas M","phones":[{"number":"+56931110001","region":"CL","primary":true}]}' -H 'If-Match: "0"'; chk REG-03 "update with current version" 200 "$CODE"
api testoperator PUT /api/customers/$ANA '{"displayName":"Stale","phones":[{"number":"+56931110001","region":"CL","primary":true}]}' -H 'If-Match: "0"'; chk REG-03 "stale If-Match -> 409" 409 "$CODE"
api testoperator DELETE /api/customers/$ANA '' -H 'If-Match: "1"'; chk REG-03 "OPERATOR cannot archive (CUSTOMER_DELETE is OWNER/ADMIN)" 403 "$CODE"
api testviewer POST /api/customers '{"displayName":"V","phones":[{"number":"+56931110005","region":"CL","primary":true}]}'; chk REG-03 "VIEWER cannot create" 403 "$CODE"
api testuser DELETE /api/customers/$ANA '' -H 'If-Match: "1"'; chk REG-03 "OWNER archives customer" 204 "$CODE"
api testoperator POST /api/customers '{"displayName":"Reuse","phones":[{"number":"+56931110001","region":"CL","primary":true}]}'; chk REG-03 "phone of an archived customer stays reserved" 409 "$CODE"
chk REG-03 "archived customer listed with status filter" ARCHIVED "$(getj testoperator '/api/customers?status=ARCHIVED' | jq -r '.customers[0].status')"
TENANT=$SUR; api testuser POST /api/customers '{"displayName":"Ana Sur","phones":[{"number":"+56931110001","region":"CL","primary":true}]}'; chk REG-03 "same phone allowed in another tenant" 201 "$CODE"; TENANT=$NORTE
chk REG-03 "search by phone finds the customer" "Ana CR" "$(getj testoperator '/api/customers?phone=88881234&region=CR' | jq -r '.customers[0].displayName')"
chk REG-03 "search by name" "Intl US" "$(getj testoperator '/api/customers?name=Intl' | jq -r '.customers[0].displayName')"
# ---------------- REG-04 ----------------
api testoperator POST /api/customers '{"displayName":"Consent QA","phones":[{"number":"+56931120001","region":"CL","primary":true}]}'; CID=$(echo "$BODY"|jq -r .id); PID=$(echo "$BODY"|jq -r '.phones[0].id')
el() { getj testoperator "/api/customers/$CID/contact-eligibility?channel=WHATSAPP&contactId=$1" | jq -c '[.eligible, (.reasons|join(","))]'; }
chk REG-04 "initial consent UNKNOWN / not eligible" '[false,"CONSENT_UNKNOWN"]' "$(el $PID)"
api testoperator PUT /api/customers/$CID/contacts/$PID/consents/WHATSAPP '{"status":"GRANTED","source":"CUSTOMER_VERBAL"}' -H 'If-Match: "0"'; chk REG-04 "grant consent" 200 "$CODE"; chk REG-04 "granted -> eligible" '[true,""]' "$(el $PID)"
api testoperator PUT /api/customers/$CID/do-not-contact '{"enabled":true,"source":"CUSTOMER_WRITTEN"}' -H 'If-Match: "1"'; chk REG-04 "do-not-contact on" 200 "$CODE"; chk REG-04 "DNC overrides granted consent" '[false,"DO_NOT_CONTACT"]' "$(el $PID)"
api testoperator PUT /api/customers/$CID/contacts/$PID/consents/WHATSAPP '{"status":"REVOKED","source":"CUSTOMER_WRITTEN"}' -H 'If-Match: "2"'; chk REG-04 "revoke consent while DNC on" 200 "$CODE"
api testoperator PUT /api/customers/$CID/do-not-contact '{"enabled":false,"source":"OPERATOR_CORRECTION"}' -H 'If-Match: "3"'; chk REG-04 "clear DNC" 200 "$CODE"; chk REG-04 "cleared DNC with revoked consent stays not eligible" '[false,"CONSENT_REVOKED"]' "$(el $PID)"
api testoperator PUT /api/customers/$CID/do-not-contact '{"enabled":true,"source":"OPERATOR_CORRECTION"}' -H 'If-Match: "1"'; chk REG-04 "stale policy If-Match -> 409" 409 "$CODE"
api testviewer PUT /api/customers/$CID/do-not-contact '{"enabled":true,"source":"OPERATOR_CORRECTION"}' -H 'If-Match: "4"'; chk REG-04 "VIEWER cannot change policy" 403 "$CODE"
api testoperator PUT /api/customers/$CID '{"displayName":"Consent QA","phones":[{"number":"+56931120002","region":"CL","primary":true}]}' -H 'If-Match: "0"'; chk REG-04 "change contact identity" 200 "$CODE"
chk REG-04 "consent not transferred to the new contact" "UNKNOWN" "$(getj testoperator /api/customers/$CID/contact-policy | jq -r '.consents[0].status')"
# ---------------- REG-05 ----------------
PURCH=/api/customers/$CID/purchases
mkp() { api testoperator POST $PURCH "{\"purchasedAt\":\"$1\",\"description\":\"$2\"}" -H "Idempotency-Key: $3"; }
mkp 2026-08-01T15:00:00Z "Compra agosto" p-a; chk REG-05 "record purchase" 201 "$CODE"; PA=$(echo "$BODY"|jq -r .id)
mkp 2026-09-20T15:00:00Z "Compra septiembre" p-b; PB=$(echo "$BODY"|jq -r .id)
mkp 2026-06-01T15:00:00Z "Compra backdated junio" p-c; chk REG-05 "record backdated purchase" 201 "$CODE"
last() { getj testoperator $PURCH/last | jq -r .description; }
chk REG-05 "last purchase = latest valid by date" "Compra septiembre" "$(last)"
mkp 2026-08-01T15:00:00Z "Compra agosto" p-a; chk REG-05 "idempotent replay returns 200" 200 "$CODE"
api testoperator POST $PURCH '{"purchasedAt":"2026-08-02T15:00:00Z","description":"X"}' -H "Idempotency-Key: p-a"; chk REG-05 "same key + different body -> 409" 409 "$CODE"
mkp "$(date -u -d '+10 years' +%Y-%m-%dT00:00:00Z)" "futuro" p-d; chk REG-05 "future date rejected" 400 "$CODE"
api testoperator PUT $PURCH/$PB '{"purchasedAt":"2026-07-01T15:00:00Z","description":"Septiembre corregida"}' -H 'If-Match: "0"'; chk REG-05 "correct purchase date" 200 "$CODE"
chk REG-05 "last purchase after correction" "Compra agosto" "$(last)"
api testoperator DELETE $PURCH/$PA '' -H 'If-Match: "0"'; chk REG-05 "void latest purchase" 204 "$CODE"
chk REG-05 "last purchase after voiding the latest" "Septiembre corregida" "$(last)"
chk REG-05 "history keeps RECORDED and VOIDED" '["VOIDED","RECORDED"]' "$(getj testoperator $PURCH/$PA/history | jq -c '[.events[].type]')"
chk REG-05 "list shows VOID and VALID statuses" "VALID VOID" "$(getj testoperator $PURCH | jq -r '[.purchases[].status]|unique|join(" ")')"
# ---------------- REG-06 ----------------
PID2=$(getj testoperator /api/customers/$CID | jq -r '.phones[0].id'); V=$(getj testoperator /api/customers/$CID/contact-policy | jq .version)
api testoperator PUT /api/customers/$CID/contacts/$PID2/consents/WHATSAPP '{"status":"GRANTED","source":"CUSTOMER_VERBAL"}' -H "If-Match: \"$V\""
ev() { getj testoperator /api/customers/$CID/follow-up-eligibility | jq -r "$1"; }
fpv() { api testoperator GET /api/customers/$CID/follow-up-policy ''; etag; }
chk REG-06 "overdue from last valid purchase + tenant cadence" "OVERDUE LAST_PURCHASE" "$(ev '[.status,.timingSource]|join(" ")')"
chk REG-06 "queue lists the overdue customer" "Consent QA" "$(getj testoperator '/api/follow-up-queue?status=OVERDUE' | jq -r '.items[]|select(.displayName=="Consent QA")|.displayName')"
TODAY=$(ev .tenantDate); api testoperator PUT /api/customers/$CID/follow-up-policy "{\"cadenceDays\":null,\"explicitNextDate\":\"$TODAY\"}" -H "If-Match: \"$(fpv)\""; chk REG-06 "set explicit next date = tenant today" 200 "$CODE"
chk REG-06 "explicit date today -> DUE" "DUE EXPLICIT_DATE" "$(ev '[.status,.timingSource]|join(" ")')"
expz() { local td=$(TZ=$1 date +%F); if [ "$td" \> "$TODAY" ]; then echo OVERDUE; elif [ "$td" = "$TODAY" ]; then echo DUE; else echo NOT_YET_DUE; fi; }
setz() { api testuser GET /api/follow-up-policy ''; local tv=$(etag); api testuser PUT /api/follow-up-policy "{\"cadenceDays\":30,\"timeZone\":\"$1\"}" -H "If-Match: \"$tv\""; }
setz Pacific/Kiritimati; chk REG-06 "tenant timezone Pacific/Kiritimati accepted" 200 "$CODE"; chk REG-06 "date boundary UTC+14 (expected computed from the real zone)" "$(expz Pacific/Kiritimati)" "$(ev .status)"
setz Pacific/Pago_Pago; chk REG-06 "date boundary UTC-11 (expected computed from the real zone)" "$(expz Pacific/Pago_Pago)" "$(ev .status)"
setz Mars/Base; chk REG-06 "invalid timezone -> 400" 400 "$CODE"
setz UTC; chk REG-06 "restore UTC" 200 "$CODE"
api testuser GET /api/follow-up-policy ''; TV=$(etag); api testuser PUT /api/follow-up-policy '{"cadenceDays":0,"timeZone":"UTC"}' -H "If-Match: \"$TV\""; chk REG-06 "cadence 0 rejected" 400 "$CODE"
api testoperator PUT /api/customers/$CID/follow-up-snooze '{"until":"2026-01-01"}' -H "If-Match: \"$(fpv)\""; chk REG-06 "snooze to the past -> 400" 400 "$CODE"
FUT=$(date -u -d '+5 days' +%F 2>/dev/null || date -u -v+5d +%F)
api testoperator PUT /api/customers/$CID/follow-up-snooze "{\"until\":\"$FUT\"}" -H "If-Match: \"$(fpv)\""; chk REG-06 "snooze a DUE customer" 200 "$CODE"
chk REG-06 "snoozed customer is NOT_YET_DUE" "NOT_YET_DUE" "$(ev .status)"
api testoperator POST /api/customers/$CID/follow-up-dismissals '{"notes":"x"}' -H "If-Match: \"$(fpv)\"" -H "Idempotency-Key: reg06-dis-x"; chk REG-06 "dismiss a NOT_YET_DUE customer -> 409" 409 "$CODE"
mkdue() { api testoperator POST /api/customers "{\"displayName\":\"$1\",\"phones\":[{\"number\":\"$2\",\"region\":\"CL\",\"primary\":true}]}"; local id=$(echo "$BODY"|jq -r .id) pid=$(echo "$BODY"|jq -r '.phones[0].id'); api testoperator PUT /api/customers/$id/contacts/$pid/consents/WHATSAPP '{"status":"GRANTED","source":"CUSTOMER_VERBAL"}' -H 'If-Match: "0"' >/dev/null; api testoperator POST /api/customers/$id/purchases '{"purchasedAt":"2026-08-10T14:00:00Z","description":"Café 1 kg"}' -H "Idempotency-Key: due-$id" >/dev/null; echo $id; }
D1=$(mkdue "Dismiss QA" +56931130001); D2=$(mkdue "Complete QA" +56931130002)
pv() { api testoperator GET /api/customers/$1/follow-up-policy ''; etag; }
api testoperator POST /api/customers/$D1/follow-up-dismissals '{"notes":"No interesado"}' -H "If-Match: \"$(pv $D1)\"" -H "Idempotency-Key: reg06-dis-1"; chk REG-06 "dismiss a DUE/OVERDUE customer (201)" 201 "$CODE"; DISM=$(echo "$BODY"|jq -r .id)
api testoperator POST /api/customers/$D1/follow-up-dismissals '{"notes":"No interesado"}' -H "If-Match: \"$(pv $D1)\"" -H "Idempotency-Key: reg06-dis-1"; chk REG-06 "dismissal replay (same key) -> 200, same record" "200 $DISM" "$CODE $(echo "$BODY"|jq -r .id)"
chk REG-06 "dismissed customer advances to NOT_YET_DUE" "NOT_YET_DUE" "$(getj testoperator /api/customers/$D1/follow-up-eligibility | jq -r .status)"
api testoperator POST /api/customers/$D2/manual-follow-ups '{"notes":"Llamada"}' -H "If-Match: \"$(pv $D2)\"" -H "Idempotency-Key: reg06-comp-1"; chk REG-06 "manual completion (201)" 201 "$CODE"; COMP=$(echo "$BODY"|jq -r .id)
api testoperator POST /api/customers/$D2/manual-follow-ups '{"notes":"otra"}' -H "If-Match: \"$(pv $D2)\"" -H "Idempotency-Key: reg06-comp-1"; chk REG-06 "completion replay returns original record (200)" "200 $COMP" "$CODE $(echo "$BODY"|jq -r .id)"
api testoperator POST /api/customers/$D2/manual-follow-ups '{}' -H 'If-Match: "0"' -H "Idempotency-Key: reg06-comp-2"; chk REG-06 "stale If-Match on disposition -> 409" 409 "$CODE"
api testoperator POST /api/customers/$D2/manual-follow-ups '{}' -H "If-Match: \"$(pv $D2)\"" -H "Idempotency-Key: bad key!"; chk REG-06 "invalid idempotency key -> 400" 400 "$CODE"
chk REG-06 "single completion recorded (no duplicate on replay)" 1 "$(psqlq "select count(*) from dokene.manual_follow_up_completions where customer_id='$D2'")"
# ---------------- tenant policy roles (#159) ----------------
api testuser GET /api/follow-up-policy ''; TV=$(etag)
api testoperator PUT /api/follow-up-policy '{"cadenceDays":30,"timeZone":"UTC"}' -H "If-Match: \"$TV\""; chk REG-06 "OPERATOR cannot change the tenant-wide policy (#159)" 403 "$CODE"
api testviewer PUT /api/follow-up-policy '{"cadenceDays":30,"timeZone":"UTC"}' -H "If-Match: \"$TV\""; chk REG-06 "VIEWER cannot change the tenant-wide policy" 403 "$CODE"
echo; echo "SUMMARY pass=$PASSN fail=$FAILN"
[ "$FAILN" -eq 0 ]  # non-zero exit status when any check failed
