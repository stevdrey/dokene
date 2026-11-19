#!/usr/bin/env bash
# ABOUTME: Suite #340 close-out: archives every active QA340 customer in both workspaces through the API (OWNER/ADMIN),
# ABOUTME: then records the end state: provider mode, switches, stub sends, open QA340 messages and active fixtures.
set -uo pipefail
. "$(dirname "$0")/qa340.sh"
C=CLEANUP
qa_login testuser || exit 1
n=0; bad=0
for T in "$A" "$B"; do
  for id in $(psqlq "select id from dokene.customers where tenant_id='$T' and status='ACTIVE' and display_name like 'QA340 %'"); do
    req testuser "$T" GET "/api/customers/$id" -
    req testuser "$T" DELETE "/api/customers/$id" - -H "If-Match: $(etag)"
    [ "$CODE" = 204 ] && n=$((n + 1)) || bad=$((bad + 1))
  done
done
chk $C "every active QA340 customer archived through the API" "0 0" "$bad $(psqlq "select count(*) from dokene.customers where status='ACTIVE' and display_name like 'QA340 %'")" "archived_now=$n"
chk $C "no open (pending/approved) QA340 message left" 0 "$(psqlq "select count(*) from dokene.outbound_messages m join dokene.customers c on c.id=m.customer_id where c.display_name like 'QA340 %' and m.status in ('PENDING_APPROVAL','APPROVED')")"
chk $C "AI provider back to fake, global outbound off" "provider=fake outbound=false" "$(docker compose -f "$WT/compose.yaml" exec -T backend sh -c 'echo provider=$DOKENE_AI_PROVIDER outbound=$DOKENE_MESSAGING_OUTBOUND_ENABLED')"
req testuser "$A" GET /api/integrations/whatsapp -; chk $C "tenant A outbound switch off" false "$(jb .outboundEnabled)"
chk $C "provider send requests over the whole suite" "$STUB0_340" "$(stub_sends)"
chk $C "runtime role SELECT grants restored after #293 probes" "t t" "$(psqlq "select has_table_privilege('dokene_runtime','dokene.tenant_provider_templates','SELECT')") $(psqlq "select has_table_privilege('dokene_runtime','dokene.tenant_messaging_integrations','SELECT')")"
rec $C "AI invocations in the suite by operation:outcome:detail (GENERATED = live OpenAI)" NOTE \
  "$(psqlq "select string_agg(k||'='||n, ', ' order by k) from (select ai_operation||':'||ai_outcome||':'||coalesce(ai_detail,'-') k, count(*) n from (select * from dokene.audit_events where event_type='AI_INVOCATION_OUTCOME' order by occurred_at desc limit $(( $(ai_n) - AI0_340 ))) x group by 1) y")"
qa_finish
