#!/usr/bin/env bash
# ABOUTME: P3-MQA-SEC-02 part 2: customer searches across tenants (with positive controls), and the containment of an
# ABOUTME: integration that names tenant A's phone number id or credential reference from tenant B. Restores tenant B.
set -uo pipefail
. "$(dirname "$0")/qa337.sh"
C=SEC-02
assert_safe_stack false || exit 1
qa_login testuser || exit 1
IA_PHONE=$(psqlq "select normalized_phone from dokene.customer_phone_contacts where id='$IA_C'")
P=${IA_PHONE//+/%2B}
ids() { jb '[.customers[]?.id]|join(" ")'; }
req testuser "$A" GET "/api/customers?phone=$P&region=CL" -
rec $C "control: tenant A phone search finds its own customer" "$( { [ "$CODE" = 200 ] && [ "$(ids)" = "$IA" ]; } && echo PASS || echo FAIL)" "http=$CODE rows=$(jb '.customers|length')"
req testuser "$B" GET "/api/customers?phone=$P&region=CL" -
rec $C "B ctx: phone search for A's customer number finds nothing" "$( { [ "$CODE" = 200 ] && [ "$(jb '.customers|length')" = 0 ]; } && echo PASS || echo FAIL)" "http=$CODE rows=$(jb '.customers|length')"
req testuser "$B" GET "/api/customers?name=QA337" -
na=0; for i in $(ids); do [ "$(psqlq "select tenant_id from dokene.customers where id='$i'")" = "$B" ] || na=$((na + 1)); done
rec $C "B ctx: name search QA337 lists only tenant B customers" "$( { [ "$CODE" = 200 ] && [ "$(jb '.customers|length')" -ge 1 ] && [ $na = 0 ] && printf '%s' "$(ids)" | grep -q "$IB"; } && echo PASS || echo FAIL)" "http=$CODE rows=$(jb '.customers|length') foreign=$na"
req testuser "$A" GET "/api/customers?name=QA337%20Sur" -
rec $C "A ctx: name search for B's customer finds nothing" "$( { [ "$CODE" = 200 ] && [ "$(jb '.customers|length')" = 0 ]; } && echo PASS || echo FAIL)" "http=$CODE rows=$(jb '.customers|length')"
req testuser "$A" GET "/api/customers?name=QA337%20Norte" -
rec $C "control: A ctx name search finds its own customer" "$( { [ "$CODE" = 200 ] && [ "$(ids)" = "$IA" ]; } && echo PASS || echo FAIL)" "http=$CODE rows=$(jb '.customers|length')"

# --- integration containment. ADR 0024 (#210, #142): PUT stores any well-formed unverified claim without an oracle;
# a reference resolves only in the caller tenant's namespace; routing and sending use verified mappings only.
tokA() { grep -c 'token=A' "$QA_HOME/stub/requests.log"; }
verified() { psqlq "select coalesce(string_agg(case tenant_id when '$A' then 'A' when '$B' then 'B' end||':'||phone_number_id, ',' order by tenant_id),'-') from dokene.tenant_messaging_integrations where ownership_verified_at is not null"; }
putB() { req testuser "$B" GET /api/integrations/whatsapp -; req testuser "$B" PUT /api/integrations/whatsapp "$(jb '{phoneNumberId,businessAccountId,displayPhoneNumber,enabled,secretRef}' | jq -c ".phoneNumberId=\"$1\" | .secretRef=\"$2\"")" -H "If-Match: $(etag)"; }
proveB() { req testuser "$B" GET /api/integrations/whatsapp -; req testuser "$B" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: $(etag)"; }
ta0=$(tokA); v0=$(verified)
rec $C "start: only tenant A holds a verified mapping of its number" "$( { printf '%s' "$v0" | grep -q 'A:100000000000001' && ! printf '%s' "$v0" | grep -q 'B:100000000000001'; } && echo PASS || echo FAIL)" "verified=$v0"
putB 100000000000001 qa333-token-b
rec $C "B claims A's phone number id: stored unverified (by design, no oracle)" "$( { [ "$CODE" = 200 ] && [ "$(jb .webhookVerified)" = false ]; } && echo PASS || echo FAIL)" "http=$CODE webhookVerified=$(jb .webhookVerified)"
# B's own proof (if any) was cleared by the number change above, so compare the mappings that matter, not v0.
proveB
rec $C "B ownership proof of A's number is refused, A keeps the verified mapping" "$( { [ "$CODE" = 400 ] && [ "$(code)" = INVALID_INPUT ] && vf=$(verified) && printf '%s' "$vf" | grep -q 'A:100000000000001' && ! printf '%s' "$vf" | grep -q 'B:100000000000001'; } && echo PASS || echo FAIL)" "http=$CODE code=$(code) verified=$(verified)"
# A signed callback for A's number still lands in tenant A only.
W=wamid.SYNTHETIC-qa337-route-$(date +%s)
wh_body "$OUTD/route.json" 100000000000001 "$W:delivered"; wh_post "$OUTD/route.json"; sleep 2
rec $C "callback for A's number routes to tenant A only while B holds the unverified claim" "$( { [ "$WCODE" = 200 ] && [ "$(psqlq "select coalesce(string_agg(tenant_id::text,','),'-') from dokene.provider_webhook_events where provider_message_id='$W'")" = "$A" ]; } && echo PASS || echo FAIL)" "http=$WCODE tenants=$(psqlq "select count(distinct tenant_id) from dokene.provider_webhook_events where provider_message_id='$W'") cookie=$(no_cookie && echo no || echo yes)"
putB 100000000000002 qa333-token-a
rec $C "B names A's credential reference: stored as a bare reference (resolved in B's namespace only)" "$( { [ "$CODE" = 200 ] && [ "$(jb .webhookVerified)" = false ]; } && echo PASS || echo FAIL)" "http=$CODE"
st0=$(stub_all); proveB
rec $C "B proof with A's reference cannot resolve A's token: no provider request is made" "$( { [ "${CODE:0:1}" = 5 ] || [ "$CODE" = 400 ]; } && [ "$(stub_all)" = "$st0" ] && [ "$(tokA)" = "$ta0" ] && echo PASS || echo FAIL)" "http=$CODE code=$(code) stub_delta=$(( $(stub_all) - st0 )) tokenA_requests_delta=$(( $(tokA) - ta0 ))"
rec $C "no stub request carried tenant A's token during the B probes" "$( { [ "$(tokA)" = "$ta0" ]; } && echo PASS || echo FAIL)" "delta=$(( $(tokA) - ta0 ))"
# Restore tenant B to its own number and credential and prove it again (a phone-list read at the stub, never a send).
putB 100000000000002 qa333-token-b; proveB
rec $C "cleanup: tenant B back on its own number, re-proven" "$( { [ "$CODE" = 200 ] && [ "$(jb .webhookVerified)" = true ] && [ "$(jb .secretRef)" = qa333-token-b ]; } && echo PASS || echo FAIL)" "http=$CODE verified=$(verified)"
# Recorded FAIL lines make the exit status nonzero.
qa_finish
