#!/usr/bin/env bash
# REG-06 recurrence: several follow-up cycles for one customer. Elapsed time is SIMULATED by shifting the stored
# disposition dates back in the database (no clock control exists in the running app); everything else goes through the API.
# Run from the repository root with the stack up.
set -a; . ./.env; set +a; export QA_TMP=$(mktemp -d); . "$(dirname "$0")/qa.sh"
for u in testuser testoperator; do qa_login $u; done
TENANT=$(curl -s -b $QA_TMP/jar-testoperator.txt $BFF/api/tenants | jq -r '.[0].tenantId')
api testoperator POST /api/customers "{\"displayName\":\"Recurrence QA $RUN\",\"phones\":[{\"number\":\"$(rnd_phone)\",\"region\":\"CL\",\"primary\":true}]}"; CID=$(echo "$BODY"|jq -r .id); PID=$(echo "$BODY"|jq -r '.phones[0].id')
api testoperator PUT /api/customers/$CID/contacts/$PID/consents/WHATSAPP '{"status":"GRANTED","source":"CUSTOMER_VERBAL"}' -H 'If-Match: "0"' >/dev/null
api testoperator POST /api/customers/$CID/purchases '{"purchasedAt":"2026-06-01T14:00:00Z","description":"Cafe"}' -H "Idempotency-Key: rec-p-$RUN" >/dev/null
TODAY=$(getj testoperator /api/customers/$CID/follow-up-eligibility | jq -r .tenantDate)
d() { date -u -d "$1 $2 days" +%F 2>/dev/null || date -u -j -v${2:+$2d} -f %F "$1" +%F; }
ev() { getj testoperator /api/customers/$CID/follow-up-eligibility | jq -r "$1"; }
pv() { api testoperator GET /api/customers/$CID/follow-up-policy ''; etag; }
shift_days() { psqlq "update dokene.customer_follow_up_policies set last_manual_follow_up_date = last_manual_follow_up_date - $1, last_dismissed_date = last_dismissed_date - $1 where customer_id='$CID'; update dokene.manual_follow_up_completions set completed_on = completed_on - $1 where customer_id='$CID'; update dokene.follow_up_dismissals set dismissed_on = dismissed_on - $1 where customer_id='$CID'" >/dev/null; }
comp() { api testoperator POST /api/customers/$CID/manual-follow-ups "{\"notes\":\"cycle $1\"}" -H "If-Match: \"$(pv)\"" -H "Idempotency-Key: rec-c$1-$RUN"; }
chk REC "cycle 1: overdue from the old purchase" "OVERDUE LAST_PURCHASE" "$(ev '[.status,.timingSource]|join(" ")')"
comp 1; chk REC "cycle 1: completion recorded today" "201 $TODAY" "$CODE $(echo "$BODY"|jq -r .completedOn)"; K1=$(echo "$BODY"|jq -r .id)
chk REC "cycle 1: NOT_YET_DUE, next = today+30 (LAST_MANUAL_FOLLOW_UP)" "NOT_YET_DUE $(d $TODAY 30) LAST_MANUAL_FOLLOW_UP" "$(ev '[.status,.nextFollowUpDate,.timingSource]|join(" ")')"
chk REC "cycle 1: customer left the queue" 0 "$(getj testoperator '/api/follow-up-queue?limit=100' | jq "[.items[]|select(.customerId==\"$CID\")]|length")"
shift_days 31   # simulate 31 days passing
chk REC "after +31 days: OVERDUE by one day, still anchored on the manual follow-up" "OVERDUE $(d $TODAY -1) LAST_MANUAL_FOLLOW_UP" "$(ev '[.status,.nextFollowUpDate,.timingSource]|join(" ")')"
chk REC "after +31 days: back in the queue as OVERDUE" OVERDUE "$(getj testoperator '/api/follow-up-queue?limit=100' | jq -r ".items[]|select(.customerId==\"$CID\")|.status")"
api testoperator POST /api/customers/$CID/manual-follow-ups '{"notes":"replay"}' -H "If-Match: \"$(pv)\"" -H "Idempotency-Key: rec-c1-$RUN"; chk REC "replaying the cycle-1 key returns the first record, not a new completion" "200 $K1" "$CODE $(echo "$BODY"|jq -r .id)"
comp 2; chk REC "cycle 2: second completion recorded today" "201 $TODAY" "$CODE $(echo "$BODY"|jq -r .completedOn)"
chk REC "cycle 2: NOT_YET_DUE, next = today+30" "NOT_YET_DUE $(d $TODAY 30)" "$(ev '[.status,.nextFollowUpDate]|join(" ")')"
chk REC "two completions stored (history kept)" 2 "$(psqlq "select count(*) from dokene.manual_follow_up_completions where customer_id='$CID'")"
shift_days 31
chk REC "cycle 3 due again (+31 days)" OVERDUE "$(ev .status)"
api testoperator POST /api/customers/$CID/follow-up-dismissals '{"notes":"cycle 3 dismissal"}' -H "If-Match: \"$(pv)\"" -H "Idempotency-Key: rec-d3-$RUN"; chk REC "cycle 3: dismissal recorded" 201 "$CODE"
chk REC "after the dismissal the anchor is the dismissal (latest of the anchors)" "NOT_YET_DUE $(d $TODAY 30) LAST_DISMISSAL" "$(ev '[.status,.nextFollowUpDate,.timingSource]|join(" ")')"
shift_days 31
chk REC "cycle 4 due again (+31 days)" OVERDUE "$(ev .status)"
comp 4; chk REC "cycle 4: completion after a dismissal re-anchors on the completion" "201 NOT_YET_DUE $(d $TODAY 30) LAST_MANUAL_FOLLOW_UP" "$CODE $(ev '[.status,.nextFollowUpDate,.timingSource]|join(" ")')"
chk REC "history: 3 completions + 1 dismissal" "3 1" "$(psqlq "select (select count(*) from dokene.manual_follow_up_completions where customer_id='$CID'), (select count(*) from dokene.follow_up_dismissals where customer_id='$CID')" | tr '|' ' ')"
echo; echo "SUMMARY pass=$PASSN fail=$FAILN"
