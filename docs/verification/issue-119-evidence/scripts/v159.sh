#!/usr/bin/env bash
# Issue #159: tenant-wide follow-up policy restricted to OWNER/ADMIN (TENANT_UPDATE). Run from the repository root with the stack up.
set -a; . ./.env; set +a; export QA_TMP=$(mktemp -d); . "$(dirname "$0")/qa.sh"
for u in testuser testoperator testviewer; do qa_login $u; done
TENANT=$(curl -s -b $QA_TMP/jar-testoperator.txt $BFF/api/tenants | jq -r '.[0].tenantId')
pol() { getj testuser /api/follow-up-policy | jq -c '[.cadenceDays,.timeZone]'; }
ver() { api testuser GET /api/follow-up-policy ''; etag; }
den() { psqlq "select count(*) from dokene.audit_events where event_type='AUTHORIZATION_DENIED' and permission='TENANT_UPDATE'"; }
ok_changes() { psqlq "select count(*) from dokene.audit_events where event_type='TENANT_FOLLOW_UP_POLICY_CHANGED'"; }
# normalise baseline
V=$(ver); api testuser PUT /api/follow-up-policy '{"cadenceDays":30,"timeZone":"UTC"}' -H "If-Match: \"$V\""
chk T159 "baseline tenant policy" '[30,"UTC"]' "$(pol)"; V0=$(ver); D0=$(den); C0=$(ok_changes)
api testoperator PUT /api/follow-up-policy '{"cadenceDays":10,"timeZone":"Pacific/Kiritimati"}' -H "If-Match: \"$V0\""; chk T159 "OPERATOR PUT tenant policy -> 403" 403 "$CODE"
api testviewer PUT /api/follow-up-policy '{"cadenceDays":10,"timeZone":"Pacific/Kiritimati"}' -H "If-Match: \"$V0\""; chk T159 "VIEWER PUT tenant policy -> 403" 403 "$CODE"
chk T159 "denied attempts changed nothing" "[30,\"UTC\"]|$V0" "$(pol)|$(ver)"
api testoperator PUT /api/follow-up-policy '{"cadenceDays":10,"timeZone":"UTC"}'; chk T159 "OPERATOR PUT without If-Match -> 403 (#162)" 403 "$CODE"
api testoperator PUT /api/follow-up-policy '{"cadenceDays":0,"timeZone":"Mars/Base"}' -H "If-Match: \"$V0\""; chk T159 "OPERATOR PUT with an invalid body -> 403 (#162)" 403 "$CODE"
chk T159 "4 denials audited with TENANT_UPDATE" 4 "$(( $(den) - D0 ))"
api testoperator GET /api/follow-up-policy ''; chk T159 "OPERATOR GET tenant policy -> 200" 200 "$CODE"
api testviewer GET /api/follow-up-policy ''; chk T159 "VIEWER GET tenant policy -> 200" 200 "$CODE"
api testuser PUT /api/follow-up-policy '{"cadenceDays":21,"timeZone":"America/Costa_Rica"}' -H "If-Match: \"$V0\""; chk T159 "OWNER PUT tenant policy -> 200" 200 "$CODE"; V1=$(etag)
chk T159 "OWNER change applied, version +1" "[21,\"America/Costa_Rica\"]|$((V0+1))" "$(pol)|$V1"
api testuser PUT /api/follow-up-policy '{"cadenceDays":30,"timeZone":"UTC"}' -H "If-Match: \"$V0\""; chk T159 "OWNER stale If-Match -> 409" 409 "$CODE"
api testuser PUT /api/follow-up-policy '{"cadenceDays":30,"timeZone":"Mars/Base"}' -H "If-Match: \"$V1\""; chk T159 "OWNER invalid time zone -> 400" 400 "$CODE"
api testuser PUT /api/follow-up-policy '{"cadenceDays":0,"timeZone":"UTC"}' -H "If-Match: \"$V1\""; chk T159 "OWNER cadence 0 -> 400" 400 "$CODE"
OPID=$(curl -s -b $QA_TMP/jar-testoperator.txt $BFF/api/session | jq -r .identityId)
api testuser PUT /api/memberships/$OPID/role '{"role":"ADMIN"}'; chk T159 "OWNER promotes testoperator to ADMIN" 204 "$CODE"
V1=$(ver); api testoperator PUT /api/follow-up-policy '{"cadenceDays":28,"timeZone":"UTC"}' -H "If-Match: \"$V1\""; chk T159 "ADMIN PUT tenant policy -> 200" 200 "$CODE"; chk T159 "ADMIN change applied" '[28,"UTC"]' "$(pol)"
api testuser PUT /api/memberships/$OPID/role '{"role":"OPERATOR"}'; chk T159 "OWNER demotes testoperator back to OPERATOR" 204 "$CODE"
V2=$(ver); api testoperator PUT /api/follow-up-policy '{"cadenceDays":5,"timeZone":"UTC"}' -H "If-Match: \"$V2\""; chk T159 "demoted OPERATOR PUT -> 403 again" 403 "$CODE"
api testuser PUT /api/follow-up-policy '{"cadenceDays":30,"timeZone":"UTC"}' -H "If-Match: \"$V2\""; chk T159 "restore baseline (OWNER)" 200 "$CODE"; chk T159 "baseline restored" '[30,"UTC"]' "$(pol)"
chk T159 "3 successful changes audited (OWNER, ADMIN, restore)" 3 "$(( $(ok_changes) - C0 ))"
mkdue() { api testoperator POST /api/customers "{\"displayName\":\"$1\",\"phones\":[{\"number\":\"$2\",\"region\":\"CL\",\"primary\":true}]}"; local id=$(echo "$BODY"|jq -r .id) pid=$(echo "$BODY"|jq -r '.phones[0].id'); api testoperator PUT /api/customers/$id/contacts/$pid/consents/WHATSAPP '{"status":"GRANTED","source":"CUSTOMER_VERBAL"}' -H 'If-Match: "0"' >/dev/null; api testoperator POST /api/customers/$id/purchases '{"purchasedAt":"2026-08-10T14:00:00Z","description":"Café 1 kg"}' -H "Idempotency-Key: due-$id" >/dev/null; echo $id; }
pv() { api testoperator GET /api/customers/$1/follow-up-policy ''; etag; }
C1=$(mkdue "T159 Snooze" $(rnd_phone)); C2=$(mkdue "T159 Dismiss" $(rnd_phone)); C3=$(mkdue "T159 Complete" $(rnd_phone)); C4=$(mkdue "T159 CustomerPolicy" $(rnd_phone))
FUT=$(date -u -d '+5 days' +%F 2>/dev/null || date -u -v+5d +%F)
api testoperator PUT /api/customers/$C1/follow-up-snooze "{\"until\":\"$FUT\"}" -H "If-Match: \"$(pv $C1)\""; chk T159 "OPERATOR snooze still works" 200 "$CODE"
api testoperator POST /api/customers/$C2/follow-up-dismissals '{"notes":"x"}' -H "If-Match: \"$(pv $C2)\"" -H "Idempotency-Key: t159-dis-$RUN"; chk T159 "OPERATOR dismissal still works" 201 "$CODE"
api testoperator POST /api/customers/$C3/manual-follow-ups '{"notes":"x"}' -H "If-Match: \"$(pv $C3)\"" -H "Idempotency-Key: t159-comp-$RUN"; chk T159 "OPERATOR manual completion still works" 201 "$CODE"
api testoperator PUT /api/customers/$C4/follow-up-policy '{"cadenceDays":14,"explicitNextDate":null}' -H "If-Match: \"$(pv $C4)\""; chk T159 "OPERATOR per-customer policy still works" 200 "$CODE"
api testviewer PUT /api/customers/$C4/follow-up-policy '{"cadenceDays":7,"explicitNextDate":null}' -H "If-Match: \"$(pv $C4)\""; chk T159 "VIEWER per-customer policy -> 403" 403 "$CODE"
api testoperator GET /api/follow-up-queue ''; chk T159 "OPERATOR reads the queue" 200 "$CODE"
echo; echo "SUMMARY pass=$PASSN fail=$FAILN"
[ "$FAILN" -eq 0 ]  # non-zero exit status when any check failed
