#!/usr/bin/env bash
# Issue #162: authorize before request binding/validation. Run from the repository root with the stack up.
set -a; . ./.env; set +a; export QA_TMP=$(mktemp -d); . "$(dirname "$0")/qa.sh"
for u in testuser testoperator testviewer; do qa_login $u; done
denials() { psqlq "select count(*) from dokene.audit_events where event_type='AUTHORIZATION_DENIED'"; }
TENANT=$(curl -s -b $QA_TMP/jar-testoperator.txt $BFF/api/tenants | jq -r '.[0].tenantId')
PH=$(rnd_phone); api testoperator POST /api/customers "{\"displayName\":\"T162 Fixture\",\"phones\":[{\"number\":\"$PH\",\"region\":\"CL\",\"primary\":true}]}"; CID=$(echo "$BODY"|jq -r .id); PID=$(echo "$BODY"|jq -r '.phones[0].id')
api testoperator PUT /api/customers/$CID/contacts/$PID/consents/WHATSAPP '{"status":"GRANTED","source":"CUSTOMER_VERBAL"}' -H 'If-Match: "0"' >/dev/null
api testoperator POST /api/customers/$CID/purchases '{"purchasedAt":"2026-08-10T14:00:00Z","description":"Cafe"}' -H "Idempotency-Key: t162-fx-$RUN"; PUR=$(echo "$BODY"|jq -r .id)
VIEW=$(curl -s -b $QA_TMP/jar-testviewer.txt $BFF/api/session | jq -r .identityId)
echo "== unauthorized (VIEWER) + malformed requests: expect 403 and one denial audit row each =="
deny() { local desc=$1 m=$2 p=$3 d=$4; shift 4; local before=$(denials); api testviewer $m "$p" "$d" "$@"; local after=$(denials)
  chk T162 "VIEWER $desc -> 403" 403 "$CODE"; chk T162 "  denial audited ($desc)" 1 "$((after-before))"; }
deny "POST customer, invalid body" POST /api/customers '{"displayName":"  ","phones":[]}'
deny "PUT customer without If-Match" PUT /api/customers/$CID '{"displayName":"x","phones":[{"number":"+56931210099","region":"CL","primary":true}]}'
deny "DELETE customer without If-Match" DELETE /api/customers/$CID ''
deny "PUT consent without If-Match" PUT /api/customers/$CID/contacts/$PID/consents/WHATSAPP '{"status":"REVOKED","source":"CUSTOMER_WRITTEN"}'
deny "PUT consent with garbage body" PUT /api/customers/$CID/contacts/$PID/consents/WHATSAPP '{"nope":1}' -H 'If-Match: "abc"'
deny "PUT do-not-contact without If-Match" PUT /api/customers/$CID/do-not-contact '{"enabled":true,"source":"CUSTOMER_WRITTEN"}'
deny "POST purchase without Idempotency-Key" POST /api/customers/$CID/purchases '{"purchasedAt":"2026-08-10T14:00:00Z","description":"x"}'
deny "POST purchase invalid body" POST /api/customers/$CID/purchases '{}' -H "Idempotency-Key: t162-a-$RUN"
deny "PUT purchase without If-Match" PUT /api/customers/$CID/purchases/$PUR '{"purchasedAt":"2026-08-11T14:00:00Z","description":"x"}'
deny "DELETE purchase without If-Match" DELETE /api/customers/$CID/purchases/$PUR ''
deny "PUT tenant policy without If-Match" PUT /api/follow-up-policy '{"cadenceDays":1,"timeZone":"UTC"}'
deny "PUT tenant policy invalid body" PUT /api/follow-up-policy '{"cadenceDays":0,"timeZone":"Mars/Base"}' -H 'If-Match: "0"'
deny "PUT customer follow-up policy without If-Match" PUT /api/customers/$CID/follow-up-policy '{"cadenceDays":7,"explicitNextDate":null}'
deny "PUT snooze without If-Match" PUT /api/customers/$CID/follow-up-snooze '{"until":"2030-01-01"}'
deny "POST manual follow-up without headers" POST /api/customers/$CID/manual-follow-ups '{}'
deny "POST dismissal without headers" POST /api/customers/$CID/follow-up-dismissals '{}'
echo "== OPERATOR on administration endpoints, malformed =="
b=$(denials); api testoperator POST /api/memberships '{}'; a=$(denials); chk T162 "OPERATOR POST membership with invalid body -> 403" 403 "$CODE"; chk T162 "  denial audited" 1 "$((a-b))"
b=$(denials); api testoperator PUT /api/memberships/$VIEW/role '{}'; a=$(denials); chk T162 "OPERATOR change role with invalid body -> 403" 403 "$CODE"; chk T162 "  denial audited" 1 "$((a-b))"
echo "== unauthenticated =="
code=$(curl -s -o /dev/null -w '%{http_code}' -X PUT $BFF/api/follow-up-policy -H 'Content-Type: application/json' -d '{}'); chk T162 "no session, malformed -> 401" 401 "$code"
echo "== authorized callers keep validation errors (400) after authorization =="
api testoperator PUT /api/customers/$CID '{"displayName":"x","phones":[{"number":"+56931210099","region":"CL","primary":true}]}'; chk T162 "OPERATOR PUT customer without If-Match -> 400 field version" "400 version" "$CODE $(echo "$BODY"|jq -r .field)"
api testoperator POST /api/customers/$CID/purchases '{"purchasedAt":"2026-08-10T14:00:00Z","description":"x"}'; chk T162 "OPERATOR POST purchase without Idempotency-Key -> 400" 400 "$CODE"
api testoperator POST /api/customers '{"displayName":"  ","phones":[]}'; chk T162 "OPERATOR POST customer blank name -> 400" 400 "$CODE"
api testuser PUT /api/follow-up-policy '{"cadenceDays":0,"timeZone":"UTC"}' -H 'If-Match: "0"'; chk T162 "OWNER tenant policy cadence 0 -> 400" 400 "$CODE"
api testuser PUT /api/follow-up-policy '{"cadenceDays":30,"timeZone":"UTC"}'; chk T162 "OWNER tenant policy without If-Match -> 400" 400 "$CODE"
echo "== valid requests still work =="
api testoperator PUT /api/customers/$CID "{\"displayName\":\"T162 Fixture 2\",\"phones\":[{\"number\":\"$PH\",\"region\":\"CL\",\"primary\":true}]}" -H 'If-Match: "0"'; chk T162 "OPERATOR valid customer update -> 200" 200 "$CODE"
api testoperator POST /api/customers/$CID/purchases '{"purchasedAt":"2026-08-12T14:00:00Z","description":"Cafe 2"}' -H "Idempotency-Key: t162-ok-$RUN"; chk T162 "OPERATOR valid purchase -> 201" 201 "$CODE"
api testviewer GET /api/customers ''; chk T162 "VIEWER read -> 200" 200 "$CODE"
echo; echo "SUMMARY pass=$PASSN fail=$FAILN"
