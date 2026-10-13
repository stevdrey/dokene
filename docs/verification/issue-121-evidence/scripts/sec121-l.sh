#!/usr/bin/env bash
# QA suite #121 retest of #183: provisioning keeps working through the API after RLS on tenants / workspace_provisioning_records.
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa121/s/env121.sh; IK="sec121-rt-$RUN"
prov() { curl -s -m 20 -w ' %{http_code}' -b $QA_TMP/jar-$1.txt -X POST $BFF/api/tenants -H "X-CSRF-TOKEN: $(curl -s -b $QA_TMP/jar-$1.txt $BFF/api/session|jq -r .csrfToken)" -H 'Content-Type: application/json' -H "Idempotency-Key: $IK" -d "{\"displayName\":\"$2\"}"; }
r1=$(prov testuser "SEC121 RT $RUN"); r2=$(prov testuser "SEC121 RT $RUN"); r3=$(prov testoperator "other $RUN")
chk SEC-04 "provisioning: create 201, replay of the same idempotency key 200 with the same tenant" "201 200 same" "${r1##* } ${r2##* } $([ "$(echo "${r1% *}"|jq -r .tenantId)" = "$(echo "${r2% *}"|jq -r .tenantId)" ] && echo same || echo diff)"
chk SEC-04 "same idempotency key by another identity creates its own tenant (no disclosure of the first)" "201 diff" "${r3##* } $([ "$(echo "${r3% *}"|jq -r .tenantId)" != "$(echo "${r1% *}"|jq -r .tenantId)" ] && echo diff || echo same)"
chk SEC-04 "membership of the new tenant is visible through the workspace list" 1 "$(curl -s -b $QA_TMP/jar-testuser.txt $BFF/api/tenants | jq "[.[]|select(.tenantId==\"$(echo "${r1% *}"|jq -r .tenantId)\")]|length")"
echo "PASS=$PASSN FAIL=$FAILN"
