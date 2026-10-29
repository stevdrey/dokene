#!/usr/bin/env bash
# ABOUTME: Suite #149, database backstop cases P3-RLS-01 to P3-RLS-04 and P3-HOOK-13 as the real dokene_runtime role
# ABOUTME: with signed, forged and expired tenant capabilities against the running stack's PostgreSQL.
cd "$(git rev-parse --show-toplevel)"; . docs/verification/issue-149-evidence/scripts/env149.sh
D=Database
# V33 adds outbound_message_resolutions to the messaging set (T12, issue 140); it gets the same checks.
V18_TABLES="outbound_messages outbound_message_approvals outbound_message_cancellations outbound_send_attempts outbound_message_events outbound_message_idempotency_keys outbound_message_resolutions"
OTHER_TABLES="tenant_messaging_integrations tenant_provider_templates provider_webhook_events"

echo "== fixtures: messaging rows in both tenants"
read -r CA PA MA <<< "$(ready_pending $A_OPERATOR "$A" "RLS A $RUN")"
read -r CB PB MB <<< "$(ready_pending $B_OPERATOR "$B" "RLS B $RUN")"
# Rows in both tenants for every table probed below, so "0 of B" is a real isolation result and not an empty table:
# a cancellation and a T12 resolution per tenant, and a webhook inbox row parked in tenant B.
for t in "$A_OPERATOR $A_OWNER $A" "$B_OPERATOR $B_OWNER $B"; do set -- $t
  read -r c p m <<< "$(ready_pending $1 "$3" "RLS cancel $RUN")"; cancel $1 "$3" "$m"; echo "  cancellation in $3: $CODE"
  read -r c p m <<< "$(ready_unknown $1 "$3" "RLS resolve $RUN")"; resolve $2 "$3" "$m" FAILED; echo "  resolution in $3: $CODE"
done
hook_post "$(hook_body "$PN_B" "wamid.SYNTHETIC-rls-B-$RUN" delivered)" "$CANARY_APP_SECRET"; echo "  webhook inbox row for PN-B: $CODE"; sleep 2
# both_sides <count A> <count B> -> "yes yes" when the table holds rows of tenant A and of tenant B
both_sides() { echo "$([ "$1" -gt 0 ] && echo yes || echo no) $([ "$2" -gt 0 ] && echo yes || echo no)"; }
read -r PA_ SA_ <<< "$(ctx tenant "$A")"; read -r PB_ SB_ <<< "$(ctx tenant "$B")"; read -r PX_ SX_ <<< "$(ctx tenant "$B" -10)"

for t in $V18_TABLES; do id=P3-RLS-01
  na=$(psqlq "select count(*) from dokene.$t where tenant_id='$A'"); nb=$(psqlq "select count(*) from dokene.$t where tenant_id='$B'")
  chk $id $D "$t: fixture rows exist in both tenants" "yes yes" "$(both_sides "$na" "$nb")"
  chk $id $D "$t: forced RLS on" "true true" "$(psqlq "select relrowsecurity||' '||relforcerowsecurity from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='dokene' and relname='$t'")"
  chk $id $D "$t: runtime role has no DELETE, TRUNCATE, REFERENCES or TRIGGER grant" "" "$(psqlq "select string_agg(privilege_type,',') from information_schema.role_table_grants where grantee='dokene_runtime' and table_schema='dokene' and table_name='$t' and privilege_type in ('DELETE','TRUNCATE','REFERENCES','TRIGGER')")"
  chk $id $D "$t: no capability reads 0 rows" 0 "$(rt -c "select count(*) from dokene.$t")"
  chk $id $D "$t: tenant A capability reads exactly tenant A rows ($na) and 0 of B ($nb exist)" "$na 0" "$(runctx $PA_ $SA_ "select count(*) from dokene.$t") $(runctx $PA_ $SA_ "select count(*) from dokene.$t where tenant_id='$B'")"
done
chk P3-RLS-01 $D "runtime role: not superuser, no bypassrls" "false false" "$(psqlq "select rolsuper||' '||rolbypassrls from pg_roles where rolname='dokene_runtime'")"

echo "== P3-RLS-02 forged and expired capabilities for tenant B"
for t in $V18_TABLES; do
  chk P3-RLS-02 $D "$t: tampered signature reads 0, expired reads 0, re-targeted payload reads 0" "0 0 0" "$(runctx $PB_ ${SB_/a/b}x "select count(*) from dokene.$t") $(runctx $PX_ $SX_ "select count(*) from dokene.$t") $(runctx "${PA_/$A/$B}" $SA_ "select count(*) from dokene.$t")"
done
chk P3-RLS-02 $D "forged capability cannot insert an event row for tenant B" rejected "$(runctx $PB_ ${SB_/a/b}x "insert into dokene.outbound_message_events(id,tenant_id,message_id,sequence_number,event_type,status_from,status_to,occurred_at) values (gen_random_uuid(),'$B','$MB',99,'CANCELLED','PENDING_APPROVAL','CANCELLED',now())" | grep -qiE 'row-level security|violates|permission denied|null value|does not exist' && echo rejected || echo accepted)"
chk P3-RLS-02 $D "expired capability cannot update tenant B message" 0 "$(runctx $PX_ $SX_ "with u as (update dokene.outbound_messages set body='forged' where id='$MB' returning 1) select count(*) from u")"

echo "== P3-RLS-03 the owning tenant cannot rewrite what was submitted, and the event log is append only"
b0=$(psqlq "select body||'|'||recipient_phone from dokene.outbound_messages where id='$MA'")
r1=$(runctx $PA_ $SA_ "update dokene.outbound_messages set body='rewritten' where id='$MA'")
r2=$(runctx $PA_ $SA_ "update dokene.outbound_messages set recipient_phone='+56900000000' where id='$MA'")
chk P3-RLS-03 $D "body and recipient rewrite refused ('permission denied' or 0 rows) and stored values unchanged" "refused refused same" "$(echo "$r1" | grep -qiE 'permission denied|UPDATE 0|immutable' && echo refused || echo "$r1") $(echo "$r2" | grep -qiE 'permission denied|UPDATE 0|immutable' && echo refused || echo "$r2") $([ "$(psqlq "select body||'|'||recipient_phone from dokene.outbound_messages where id='$MA'")" = "$b0" ] && echo same || echo changed)"
e0=$(events_n "$MA")
r3=$(runctx $PA_ $SA_ "update dokene.outbound_message_events set event_type='APPROVED' where message_id='$MA'")
r4=$(runctx $PA_ $SA_ "delete from dokene.outbound_message_events where message_id='$MA'")
chk P3-RLS-03 $D "event update and delete denied, rows intact" "denied denied $e0" "$(echo "$r3" | grep -qi 'permission denied' && echo denied || echo "$r3") $(echo "$r4" | grep -qi 'permission denied' && echo denied || echo "$r4") $(events_n "$MA")"

echo "== P3-RLS-04 the integration, template and webhook inbox tables"
for t in $OTHER_TABLES; do id=P3-RLS-04
  if [ "$(psqlq "select count(*) from pg_tables where schemaname='dokene' and tablename='$t'")" != 1 ]; then rec $id $D BLOCKED "$t does not exist on this commit"; continue; fi
  na=$(psqlq "select count(*) from dokene.$t where tenant_id='$A'"); nb=$(psqlq "select count(*) from dokene.$t where tenant_id='$B'")
  chk $id $D "$t: fixture rows exist in both tenants" "yes yes" "$(both_sides "$na" "$nb")"
  chk $id $D "$t: forced RLS on" "true true" "$(psqlq "select relrowsecurity||' '||relforcerowsecurity from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='dokene' and relname='$t'")"
  chk $id $D "$t: no DELETE/TRUNCATE grant for the runtime role" "" "$(psqlq "select string_agg(privilege_type,',') from information_schema.role_table_grants where grantee='dokene_runtime' and table_schema='dokene' and table_name='$t' and privilege_type in ('DELETE','TRUNCATE')")"
  chk $id $D "$t: no capability 0, A capability $na and 0 of B ($nb exist), forged 0, expired 0" "0 $na 0 0 0" "$(rt -c "select count(*) from dokene.$t") $(runctx $PA_ $SA_ "select count(*) from dokene.$t") $(runctx $PA_ $SA_ "select count(*) from dokene.$t where tenant_id='$B'") $(runctx $PB_ ${SB_/a/b}x "select count(*) from dokene.$t") $(runctx $PX_ $SX_ "select count(*) from dokene.$t")"
done

echo "== P3-HOOK-13 provider attributed audit append with forged or expired provider capabilities"
read -r PP_ SP_ <<< "$(ctx provider "$A")"; read -r PPX_ SPX_ <<< "$(ctx provider "$A" -10)"
ins="insert into dokene.audit_events(id,occurred_at,tenant_id,actor_id,membership_id,event_type,target_type,target_id,outcome,correlation_id) values (gen_random_uuid(),now(),'$A',null,null,'MESSAGE_DELIVERY_UPDATED','MESSAGE','$MA','SUCCESS',gen_random_uuid())"
a0=$(audit_n MESSAGE_DELIVERY_UPDATED "$A")
chk P3-HOOK-13 $D "forged provider capability cannot append" rejected "$(runctx $PP_ ${SP_/a/b}x "$ins" | grep -qiE 'row-level security|violates|permission denied' && echo rejected || echo accepted)"
chk P3-HOOK-13 $D "expired provider capability cannot append" rejected "$(runctx $PPX_ $SPX_ "$ins" | grep -qiE 'row-level security|violates|permission denied' && echo rejected || echo accepted)"
chk P3-HOOK-13 $D "member capability cannot append a provider attributed event" rejected "$(runctx $PA_ $SA_ "$ins" | grep -qiE 'row-level security|violates|permission denied' && echo rejected || echo accepted)"
chk P3-HOOK-13 $D "audit row count unchanged" "$a0" "$(audit_n MESSAGE_DELIVERY_UPDATED "$A")"
summary
