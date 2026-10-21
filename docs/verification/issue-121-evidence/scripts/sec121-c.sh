#!/usr/bin/env bash
# QA suite #121: SEC-04 database backstop with the REAL runtime role (dokene_runtime) and signed tenant context. Disposable local QA DB, synthetic data.
# The signing key is read by the bootstrap superuser only to mint test contexts (runtime role cannot read it).
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa121/s/env121.sh; OUTD=/home/stevdrey/qa121/out; read A B C CA CA2 CB CC PA PA2 PB UO UV UU < $OUTD/ids
rt() { docker compose exec -T -e PGPASSWORD="$DOKENE_DB_PASSWORD" postgres psql -h 127.0.0.1 -U dokene_runtime -d "$DOKENE_DB_NAME" -Atq "$@" 2>&1 | tr -d '\r'; }
ctx() { psqlq "with k as (select key_id, signing_key from dokene.tenant_context_signing_keys where status='ACTIVE' limit 1), p as (select '$1|'||k.key_id||'|$2|'||lpad((extract(epoch from now())::bigint + ${3:-60})::text,10,'0')||'|'||md5(random()::text) as payload, k.signing_key from k) select payload||' '||encode(dokene.hmac(convert_to(payload,'UTF8'), signing_key,'sha256'),'hex') from p"; }
run() { local p=$1 s=$2; shift 2; printf '%s\n%s\n' "select set_config('dokene.tenant_context','$p',false), set_config('dokene.tenant_context_signature','$s',false) \\g /dev/null" "$*" | docker compose exec -T -e PGPASSWORD="$DOKENE_DB_PASSWORD" postgres psql -h 127.0.0.1 -U dokene_runtime -d "$DOKENE_DB_NAME" -Atq 2>&1 | tr -d '\r'; }
echo "== role attributes (actual runtime role)"
chk SEC-04 "runtime role is not superuser / bypassrls / createrole / createdb" "f f f f" "$(psqlq "select rolsuper,rolbypassrls,rolcreaterole,rolcreatedb from pg_roles where rolname='dokene_runtime'" | tr '|' ' ')"
chk SEC-04 "runtime role owns no table in schema dokene" 0 "$(psqlq "select count(*) from pg_tables where schemaname='dokene' and tableowner='dokene_runtime'")"
chk SEC-04 "every table has RLS enabled+forced except keys/flyway/oidc mappings (#183: tenants and workspace_provisioning_records now covered)" "" "$(psqlq "select string_agg(relname,',' order by relname desc) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='dokene' and c.relkind='r' and relname not in ('flyway_schema_history','tenant_context_signing_keys','oidc_identity_mappings') and not (relrowsecurity and relforcerowsecurity)")"
chk SEC-04 "oidc_identity_mappings: runtime has no direct privilege (SECURITY DEFINER function only)" 0 "$(psqlq "select count(*) from information_schema.role_table_grants where grantee='dokene_runtime' and table_name='oidc_identity_mappings'")"
chk SEC-04 "runtime role cannot read signing keys" denied "$(rt -c 'select count(*) from dokene.tenant_context_signing_keys' | grep -qi 'permission denied' && echo denied || echo readable)"
chk SEC-04 "runtime role cannot ALTER TABLE / disable RLS" denied "$(rt -c 'alter table dokene.customers disable row level security' | grep -qiE 'must be owner|permission denied' && echo denied || echo allowed)"
chk SEC-04 "runtime role cannot create objects in schema dokene" denied "$(rt -c 'create table dokene.qa_probe(x int)' | grep -qi 'permission denied' && echo denied || echo allowed)"
chk SEC-04 "runtime role cannot SET ROLE dokene_migration" denied "$(rt -c 'set role dokene_migration' | grep -qiE 'permission denied|must be member' && echo denied || echo allowed)"
echo "== fail closed without / with bad context"
chk SEC-04 "no context: SELECT customers returns 0 rows" 0 "$(rt -c 'select count(*) from dokene.customers')"
chk SEC-04 "no context: INSERT customer rejected by RLS" rejected "$(rt -c "insert into dokene.customers(id,tenant_id,display_name,status,created_at,updated_at,version) values (gen_random_uuid(),'$A','nocontext','ACTIVE',now(),now(),0)" | grep -qiE 'row-level security|violates' && echo rejected || echo accepted)"
read PA_ SA_ <<< "$(ctx tenant $A)"; read PB_ SB_ <<< "$(ctx tenant $B)"; read PI_ SI_ <<< "$(ctx identity $A)"; read PX_ SX_ <<< "$(ctx tenant $A -10)"; read PF_ SF_ <<< "$(ctx tenant $A 600)"
NA=$(psqlq "select count(*) from dokene.customers where tenant_id='$A'"); NB=$(psqlq "select count(*) from dokene.customers where tenant_id='$B'"); echo "  (superuser view: tenant A customers=$NA, tenant B customers=$NB)"
chk SEC-04 "tenant A context sees exactly A's customers" "$NA" "$(run $PA_ $SA_ 'select count(*) from dokene.customers')"
chk SEC-04 "A context sees 0 of B's customers (tenant_id filter / primary key / purchases)" "0 0 0" "$(run $PA_ $SA_ "select count(*) from dokene.customers where tenant_id='$B'" | tr '\n' ' ')$(run $PA_ $SA_ "select count(*) from dokene.customers where id='$CB'" | tr '\n' ' ')$(run $PA_ $SA_ "select count(*) from dokene.purchases where tenant_id='$B'")"
chk SEC-04 "tenant B context sees exactly B's customers" "$NB" "$(run $PB_ $SB_ 'select count(*) from dokene.customers')"
chk SEC-04 "A context UPDATE of B's customer affects 0 rows" 0 "$(run $PA_ $SA_ "with u as (update dokene.customers set display_name='hijack' where id='$CB' returning 1) select count(*) from u")"
chk SEC-04 "A context DELETE of B's customer denied (runtime has no DELETE on customers; archive-only)" denied "$(run $PA_ $SA_ "delete from dokene.customers where id='$CB'" | grep -qi 'permission denied' && echo denied || echo allowed)"
chk SEC-04 "A context INSERT of a row for tenant B rejected" rejected "$(run $PA_ $SA_ "insert into dokene.customers(id,tenant_id,display_name,status,created_at,updated_at,version) values (gen_random_uuid(),'$B','xt','ACTIVE',now(),now(),0)" | grep -qiE 'row-level security' && echo rejected || echo accepted)"
chk SEC-04 "A context UPDATE moving an A row to tenant B rejected" rejected "$(run $PA_ $SA_ "update dokene.customers set tenant_id='$B' where id='$CA'" | grep -qiE 'row-level security' && echo rejected || echo accepted)"
chk SEC-04 "B customer unchanged in the database" "SEC B Café QA" "$(psqlq "select display_name from dokene.customers where id='$CB'")"
chk SEC-04 "forged: tampered signature -> 0 rows" 0 "$(run $PA_ ${SA_/a/b}x 'select count(*) from dokene.customers')"
chk SEC-04 "forged: A payload re-targeted to B with A's signature -> 0 rows" 0 "$(run "${PA_/$A/$B}" $SA_ 'select count(*) from dokene.customers')"
chk SEC-04 "wrong scope (identity-signed) used as tenant context -> 0 rows" 0 "$(run $PI_ $SI_ 'select count(*) from dokene.customers')"
chk SEC-04 "expired context -> 0 rows" 0 "$(run $PX_ $SX_ 'select count(*) from dokene.customers')"
chk SEC-04 "context valid for >70 s refused -> 0 rows" 0 "$(run $PF_ $SF_ 'select count(*) from dokene.customers')"
chk SEC-04 "context with no signature -> 0 rows" 0 "$(run $PA_ '' 'select count(*) from dokene.customers')"
echo "== audit append-only + tenant-scoped reads"
AE=$(psqlq "select count(*) from dokene.audit_events"); AA=$(psqlq "select count(*) from dokene.audit_events where tenant_id='$A'"); echo "  audit rows total=$AE, tenant A=$AA"
chk SEC-04 "no context: runtime sees 0 audit rows" 0 "$(rt -c 'select count(*) from dokene.audit_events')"
chk SEC-04 "tenant A context sees only tenant A audit rows" "$AA" "$(run $PA_ $SA_ 'select count(*) from dokene.audit_events')"
chk SEC-04 "tenant A context sees no audit row of tenant B" 0 "$(run $PA_ $SA_ "select count(*) from dokene.audit_events where tenant_id='$B'")"
for op in "update dokene.audit_events set outcome='X' where tenant_id='$A'" "delete from dokene.audit_events where tenant_id='$A'" "truncate dokene.audit_events"; do chk SEC-04 "runtime '${op%% dokene*}' on audit_events denied" denied "$(run $PA_ $SA_ "$op" | grep -qi 'permission denied' && echo denied || echo allowed)"; done
chk SEC-04 "audit row count unchanged by the attempts" "$AE" "$(psqlq "select count(*) from dokene.audit_events")"
chk SEC-04 "SET row_security=off does not expose rows (forced RLS)" 0 "$(run $PA_ $SA_ "set row_security=off; select count(*) from dokene.customers where tenant_id='$B'" | tail -1 | grep -E '^[0-9]+$' || echo 0)"
echo "== tenants / workspace_provisioning_records (no RLS, see #183) -- expected FAIL until fixed"
chk SEC-04 "tenants: runtime without context sees 0 tenants" 0 "$(rt -c 'select count(*) from dokene.tenants')"
chk SEC-04 "tenants: runtime without context cannot update another tenant" 0 "$(rt -c "with u as (update dokene.tenants set display_name=display_name where id='$B' returning 1) select count(*) from u")"
chk SEC-04 "workspace_provisioning_records: runtime without context reads nothing (0 rows or permission denied)" yes "$(r=$(rt -c 'select count(*) from dokene.workspace_provisioning_records'); [ "$r" = 0 ] || echo "$r" | grep -qi 'permission denied' && echo yes || echo "$r")"
echo "== post-fix #183 additional checks"
chk SEC-04 "tenants: A context sees only tenant A" "1 $A" "$(run $PA_ $SA_ 'select count(*)||chr(32)||min(id::text) from dokene.tenants')"
chk SEC-04 "tenants: A context cannot read tenant B by id" 0 "$(run $PA_ $SA_ "select count(*) from dokene.tenants where id='$B'")"
chk SEC-04 "tenants: A context UPDATE of tenant B denied or 0 rows" yes "$(r=$(run $PA_ $SA_ "with u as (update dokene.tenants set status='ARCHIVED' where id='$B' returning 1) select count(*) from u"); [ "$r" = 0 ] || echo "$r" | grep -qi 'permission denied' && echo yes || echo "$r")"
chk SEC-04 "tenants: A context DELETE of its own row denied (no DELETE privilege)" denied "$(run $PA_ $SA_ "delete from dokene.tenants where id='$A'" | grep -qi 'permission denied' && echo denied || echo allowed)"
chk SEC-04 "tenants: UPDATE is limited to the verified tenant's own row (column grants, legitimate rename/archive path)" "UPDATE 1UPDATE 0" "$(printf '%s
%s
%s
' "select set_config('dokene.tenant_context','$PA_',false), set_config('dokene.tenant_context_signature','$SA_',false) \g /dev/null" "update dokene.tenants set display_name=display_name where id='$A';" "update dokene.tenants set display_name=display_name where id='$B';" | docker compose exec -T -e PGPASSWORD="$DOKENE_DB_PASSWORD" postgres psql -h 127.0.0.1 -U dokene_runtime -d "$DOKENE_DB_NAME" -At 2>&1 | tr -d '
' | tr '
' ' ' | sed 's/ $//')"
chk SEC-04 "tenants: A context INSERT of a tenant row with another id rejected" rejected "$(run $PA_ $SA_ "insert into dokene.tenants(id,display_name,status,created_at) values (gen_random_uuid(),'qa-rogue','ACTIVE',now())" | grep -qiE 'row-level security|permission denied' && echo rejected || echo accepted)"
chk SEC-04 "tenant B status/name unchanged and ACTIVE" "ACTIVE" "$(psqlq "select status from dokene.tenants where id='$B'")"
chk SEC-04 "API workspace list still returns the user's workspaces (discovery path)" yes "$([ "$(curl -s -b $QA_TMP/jar-testuser.txt $BFF/api/tenants | jq length)" -ge 3 ] && echo yes || echo no)"
echo "PASS=$PASSN FAIL=$FAILN"
