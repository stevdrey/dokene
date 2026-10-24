#!/usr/bin/env bash
# Fixtures needed by ux09 (81 due customers) and ux10_11 (second workspace "QA Café Sur"). Synthetic data only.
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa121/s/env121.sh
tok=$(curl -s -b $QA_TMP/jar-testuser.txt $BFF/api/session|jq -r .csrfToken)
S=$(curl -s -b $QA_TMP/jar-testuser.txt -X POST $BFF/api/tenants -H "X-CSRF-TOKEN: $tok" -H 'Content-Type: application/json' -H "Idempotency-Key: ui-sur-$RUN" -d '{"displayName":"QA Café Sur"}' | jq -r .tenantId); echo "tenant Sur=$S"
TENANT=$S; api testuser POST /api/memberships "{\"identityId\":\"$UO\",\"role\":\"OPERATOR\"}"; echo "operator added to Sur: $CODE"
TENANT=$A; have=$(psqlq "select count(*) from dokene.customers where tenant_id='$A'"); echo "customers in A: $have"
for i in $(seq 1 50); do id=$(mk "Pagination Due $i $RUN" "$(rnd_phone)" ""); consent $id GRANTED >/dev/null; buy $id 2026-05-0$((i % 9 + 1))T14:00:00Z "Café $i" >/dev/null; done
echo "queue size now: $(getj testoperator '/api/follow-up-queue?limit=1' | jq -r '.total // .items|length' 2>/dev/null)"
