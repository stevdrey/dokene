#!/usr/bin/env bash
# ABOUTME: Suite #340 REG-06 security recheck of #310 (L1, merged after suite #337): 25 identical denials from one
# ABOUTME: non-member identity get the same 403 each time but write at most rows-per-key (10) AUTHORIZATION_DENIED rows.
set -uo pipefail
. "$(dirname "$0")/qa340.sh"
C=REG-06
qa_login testnone || exit 1
# A non-member has no membership, so its denial rows carry no actor or tenant (NO_TENANT_CONTEXT). Count that key's rows in
# the current 15-minute window; on this stack only this script produces it.
rows() { psqlq "select count(*) from dokene.audit_events where event_type='AUTHORIZATION_DENIED' and actor_id is null and tenant_id is null and permission='TENANT_READ' and denial_reason='NO_TENANT_CONTEXT' and occurred_at > now() - interval '15 minutes'"; }
R0=$(rows); codes=""; bodies=""
for _ in $(seq 1 25); do req testnone "$A" GET /api/customers -; codes+="$CODE "; bodies+="$(printf '%s' "$BODY" | jq -c 'del(.timestamp)' | md5sum | cut -c1-8) "; done
R1=$(rows)
chk $C "#310 25 identical denials -> 25 x 403 with one body (timestamp aside)" "403 1" "$(printf '%s\n' $codes | sort -u | paste -sd,) $(printf '%s\n' $bodies | sort -u | wc -l)"
rec $C "#310 at most 10 denial rows for the key in the 15-minute window (rows before: $R0)" "$([ "$R1" -le 10 ] && [ "$R1" -ge 1 ] && echo PASS || echo FAIL)" "rows_after=$R1 new=$((R1 - R0))"
qa_finish
