#!/usr/bin/env bash
# ABOUTME: Shared helpers for the suite #149 scripts: logins, tenant scoped requests, database probes, stub control
# ABOUTME: and the PASS/FAIL/BLOCKED recorder that writes one line per case id to results.tsv. Synthetic data only.

BFF=${BFF:-http://localhost:8080}
PW=${DOKENE_TEST_USER_PASSWORD:-testpassword}
QA_TMP=${QA_TMP:-$(mktemp -d)}
OUTD=${OUTD:?set OUTD to the output directory}
STUBDIR=${STUBDIR:?set STUBDIR to the stub control directory}
RESULTS=${RESULTS:-$OUTD/results.tsv}
RUN=${RUN:-$(date +%s | cut -c4-10)}
mkdir -p "$OUTD"

# --- sessions ---------------------------------------------------------------------------------------------------------
qa_login() { # user -> cookie jar at $QA_TMP/jar-<user>.txt
  local u=$1 j=$QA_TMP/jar-$1.txt h=$QA_TMP/h.$$ b=$QA_TMP/b.$$; rm -f "$j"
  curl -s -c "$j" -D "$h" -o "$b" "$BFF/oauth2/authorization/dokene"
  local l; l=$(grep -i '^location:' "$h" | tr -d '\r\n' | awk '{print $2}')
  curl -s -c "$j" -b "$j" -o "$b" "$l"
  local a; a=$(grep -o 'action="[^"]*"' "$b" | head -1 | cut -d'"' -f2 | sed 's/&amp;/\&/g')
  curl -s -c "$j" -b "$j" -D "$h" -o "$b" --data-urlencode "username=$u" --data-urlencode "password=$PW" "$a"
  l=$(grep -i '^location:' "$h" | tr -d '\r\n' | awk '{print $2}'); curl -s -c "$j" -b "$j" -o "$b" "$l"
  rm -f "$h" "$b"
}
csrf() { curl -s -b "$QA_TMP/jar-$1.txt" -c "$QA_TMP/jar-$1.txt" "$BFF/api/session" | jq -r .csrfToken; }
identity() { curl -s -b "$QA_TMP/jar-$1.txt" "$BFF/api/session" | jq -r .identityId; }

# api <user> <tenant|-> <METHOD> <path> <json|-> [curl args] -> CODE HDRS BODY. "-" means no header / no body.
api() {
  local u=$1 t=$2 m=$3 p=$4 d=$5; shift 5; local j=$QA_TMP/jar-$u.txt tok out; local -a a=()
  tok=$(csrf "$u")
  [ "$t" != "-" ] && a+=(-H "X-Tenant-Id: $t")
  # CSRF_MODE: "" sends the session's token, "none" omits the header, anything else is sent verbatim (P3-CSRF)
  case ${CSRF_MODE:-} in "") [ "$tok" != "null" ] && a+=(-H "X-CSRF-TOKEN: $tok");; none) ;; *) a+=(-H "X-CSRF-TOKEN: $CSRF_MODE");; esac
  [ "$d" != "-" ] && a+=(-H 'Content-Type: application/json' --data-binary "@$QA_TMP/d.$$")
  out=$(curl -s -i -b "$j" -c "$j" -X "$m" "$BFF$p" "${a[@]}" "$@" 2>/dev/null)
  CODE=$(echo "$out" | head -1 | awk '{print $2}')
  HDRS=$(echo "$out" | sed '/^\r\{0,1\}$/q')
  BODY=$(echo "$out" | sed '1,/^\r\{0,1\}$/d')
}
# req is api with the JSON written to a file first, which keeps quotes and spaces intact.
req() { # <user> <tenant|-> <METHOD> <path> <json|-> [curl args]
  local d=$5; [ "$d" != "-" ] && printf '%s' "$d" > "$QA_TMP/d.$$"
  api "$@"
}
# raw: no session at all
raw() { local out; out=$(curl -s -i "$@" 2>/dev/null); CODE=$(echo "$out" | head -1 | awk '{print $2}'); HDRS=$(echo "$out" | sed '/^\r\{0,1\}$/q'); BODY=$(echo "$out" | sed '1,/^\r\{0,1\}$/d'); }
etag() { echo "$HDRS" | grep -i '^etag' | tr -d '\r' | awk '{print $2}'; }
hdr() { echo "$HDRS" | grep -i "^$1:" | tr -d '\r' | cut -d' ' -f2-; }
getj() { curl -s -b "$QA_TMP/jar-$1.txt" -H "X-Tenant-Id: $2" "$BFF$3"; }
# problem body check: exactly type,title,status,code and the expected code
problem_keys() { echo "$BODY" | jq -r 'keys|join(",")' 2>/dev/null; }
problem_code() { echo "$BODY" | jq -r '.code // empty' 2>/dev/null; }
problem_ok() { [ "$(problem_keys)" = "code,status,title,type" ] && [ "$(problem_code)" = "$1" ] && echo ok || echo "keys=$(problem_keys) code=$(problem_code)"; }
ctype() { hdr content-type; }
# problem_exact <status> <title> <code>: CODE, media type and body are exactly the ADR 0023 §4.4 problem, nothing more.
# Compares the canonical JSON, so a missing or extra member (path, an id), a wrong value or type all fail.
problem_exact() {
  local want got mt
  want=$(jq -cnS --argjson s "$1" --arg t "$2" --arg c "$3" '{type:"about:blank",title:$t,status:$s,code:$c}')
  got=$(printf '%s' "$BODY" | jq -cS . 2>/dev/null); mt=$(ctype | cut -d';' -f1 | tr -d ' ' | tr '[:upper:]' '[:lower:]')
  if [ "$CODE" = "$1" ] && [ "$mt" = application/problem+json ] && [ "$got" = "$want" ]; then echo ok
  else echo "status=$CODE type=$mt body=${got:-${BODY:0:120}}"; fi
}

# --- database ---------------------------------------------------------------------------------------------------------
psqlq() { docker compose exec -T postgres sh -c "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -Atc \"$1\"" | tr -d '\r'; }
rt() { docker compose exec -T -e PGPASSWORD="$DOKENE_DB_PASSWORD" postgres psql -h 127.0.0.1 -U dokene_runtime -d "$DOKENE_DB_NAME" -Atq "$@" 2>&1 | tr -d '\r'; }
# signed tenant context minted with the bootstrap role (the runtime role cannot read the signing key)
ctx() { psqlq "with k as (select key_id, signing_key from dokene.tenant_context_signing_keys where status='ACTIVE' limit 1), p as (select '$1|'||k.key_id||'|$2|'||lpad((extract(epoch from now())::bigint + ${3:-60})::text,10,'0')||'|'||md5(random()::text) as payload, k.signing_key from k) select payload||' '||encode(dokene.hmac(convert_to(payload,'UTF8'), signing_key,'sha256'),'hex') from p"; }
runctx() { local p=$1 s=$2; shift 2; printf '%s\n%s\n' "select set_config('dokene.tenant_context','$p',false), set_config('dokene.tenant_context_signature','$s',false) \\g /dev/null" "$*" | docker compose exec -T -e PGPASSWORD="$DOKENE_DB_PASSWORD" postgres psql -h 127.0.0.1 -U dokene_runtime -d "$DOKENE_DB_NAME" -Atq 2>&1 | tr -d '\r'; }
audit_n() { psqlq "select count(*) from dokene.audit_events where event_type='$1'${2:+ and tenant_id='$2'}"; }
audit_denied_n() { psqlq "select count(*) from dokene.audit_events where event_type='AUTHORIZATION_DENIED'${1:+ and permission='$1'}${2:+ and tenant_id='$2'}"; }
attempts_n() { psqlq "select count(*) from dokene.outbound_send_attempts${1:+ where message_id='$1'}"; }
events_n() { psqlq "select count(*) from dokene.outbound_message_events${1:+ where message_id='$1'}"; }
keys_n() { psqlq "select count(*) from dokene.outbound_message_idempotency_keys${1:+ where tenant_id='$1'}"; }
messages_n() { psqlq "select count(*) from dokene.outbound_messages${1:+ where tenant_id='$1'}"; }
msg_status() { psqlq "select status||'/'||version||'/'||outcome_unknown from dokene.outbound_messages where id='$1'"; }
resolutions_n() { psqlq "select count(*) from dokene.outbound_message_resolutions${1:+ where message_id='$1'}"; }
hooks_n() { psqlq "select count(*) from dokene.provider_webhook_events${1:+ where tenant_id='$1'}"; }

# --- stub control -----------------------------------------------------------------------------------------------------
stub_set() { printf '%s' "$2" > "$STUBDIR/$1"; }
stub_reset() { for f in send templates phones delay.ms; do rm -f "$STUBDIR/$f"; done; }
stub_calls() { if [ -f "$STUBDIR/requests.log" ]; then grep -c "kind=${1:-}" "$STUBDIR/requests.log" || true; else echo 0; fi; }
stub_mark() { stub_calls "$1"; }
stub_since() { echo $(( $(stub_calls "$1") - $2 )); }
backend_log() { docker compose logs --no-color --no-log-prefix backend 2>/dev/null; }

# --- recorder ---------------------------------------------------------------------------------------------------------
PASSN=0; FAILN=0; BLOCKN=0
rec() { # <case id> <channel> <PASS|FAIL|BLOCKED> <note>
  printf '%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$(date -u +%FT%TZ)" "$4" >> "$RESULTS"
  case $3 in PASS) PASSN=$((PASSN+1));; FAIL) FAILN=$((FAILN+1));; BLOCKED) BLOCKN=$((BLOCKN+1));; esac
  echo "$3  $1  [$2]  $4"
}
chk() { # <case id> <channel> <what> <expected> <actual>  -> PASS or FAIL with both values
  if [ "$4" = "$5" ]; then rec "$1" "$2" PASS "$3"; else rec "$1" "$2" FAIL "$3 (expected '$4', got '$5')"; fi
}
summary() { echo "== $(basename "$0"): PASS=$PASSN FAIL=$FAILN BLOCKED=$BLOCKN"; }

# --- fixtures over the API --------------------------------------------------------------------------------------------
rnd_phone() { printf '+5699%07d' $(( (RANDOM * 32768 + RANDOM) % 10000000 )); }
# mk_customer <user> <tenant> <name> [phone] -> prints "customerId contactId"
mk_customer() {
  local phone=${4:-$(rnd_phone)}
  req "$1" "$2" POST /api/customers "$(jq -nc --arg n "$3" --arg p "$phone" '{displayName:$n,phones:[{number:$p,region:"CL",primary:true}]}')"
  # the region validator rejects a few generated numbers; one retry with a fresh number keeps the suite moving
  [ "$CODE" = 400 ] && [ -z "${4:-}" ] && req "$1" "$2" POST /api/customers "$(jq -nc --arg n "$3" --arg p "$(rnd_phone)" '{displayName:$n,phones:[{number:$p,region:"CL",primary:true}]}')"
  [ "$CODE" = 201 ] || { echo "mk_customer failed: $CODE $BODY" >&2; return 1; }
  local cid; cid=$(echo "$BODY" | jq -r .id)
  echo "$cid $(getj "$1" "$2" "/api/customers/$cid" | jq -r '.phones[0].id')"
}
contact_policy_version() { getj "$1" "$2" "/api/customers/$3/contact-policy" | jq -r .version; }
# consent <user> <tenant> <customer> <contact> <GRANTED|REVOKED>
consent() { req "$1" "$2" PUT "/api/customers/$3/contacts/$4/consents/WHATSAPP" "{\"status\":\"$5\",\"source\":\"CUSTOMER_VERBAL\"}" -H "If-Match: \"$(contact_policy_version "$1" "$2" "$3")\""; }
dnc() { req "$1" "$2" PUT "/api/customers/$3/do-not-contact" "{\"enabled\":$4,\"source\":\"CUSTOMER_VERBAL\"}" -H "If-Match: \"$(contact_policy_version "$1" "$2" "$3")\""; }
archive_customer() { req "$1" "$2" DELETE "/api/customers/$3" - -H "If-Match: \"$(getj "$1" "$2" "/api/customers/$3" | jq -r .version)\""; }
manual_follow_up() { req "$1" "$2" POST "/api/customers/$3/manual-follow-ups" '{"notes":"QA manual follow up"}' -H "Idempotency-Key: qa149-$RUN-$RANDOM$RANDOM" -H "If-Match: $(fpv "$1" "$2" "$3")"; }
# counts that a denied or refused request must leave unchanged: messages events keys attempts audit stub-sends
snap() { echo "$(messages_n) $(events_n) $(keys_n) $(attempts_n) $(psqlq 'select count(*) from dokene.audit_events') $(stub_calls send)"; }
# unchanged <snapshot> [audit delta allowed] -> "ok" when nothing but the allowed audit rows was written
unchanged() { local now; now=$(snap); local a=($1) b=($now) want=${2:-0}
  [ "$want" = any ] && want=$(( ${b[4]} - ${a[4]} ))
  [ "${a[0]}" = "${b[0]}" ] && [ "${a[1]}" = "${b[1]}" ] && [ "${a[2]}" = "${b[2]}" ] && [ "${a[3]}" = "${b[3]}" ] && [ $(( ${b[4]} - ${a[4]} )) = "$want" ] && [ "${a[5]}" = "${b[5]}" ] && echo ok || echo "before=[$1] after=[$now]"; }
# denied <id> <channel> <what> <permission> <snapshot>: 403, exact problem body, one AUTHORIZATION_DENIED row, nothing else written
denied() {
  chk "$1" "$2" "$3: 403" 403 "$CODE"
  chk "$1" "$2" "$3: problem body FORBIDDEN" ok "$(problem_ok FORBIDDEN)"
  chk "$1" "$2" "$3: nothing written except the denial audit row" ok "$(unchanged "$5" 1)"
  chk "$1" "$2" "$3: the new audit row is AUTHORIZATION_DENIED naming $4" "AUTHORIZATION_DENIED|$4|INSUFFICIENT_PERMISSION" "$(psqlq "select event_type||'|'||coalesce(permission,'')||'|'||coalesce(denial_reason,'') from dokene.audit_events order by occurred_at desc limit 1")"
}
# refused <id> <channel> <what> <code> <status> <snapshot>: closed code, exact problem body, nothing written
refused() { chk "$1" "$2" "$3: $5 $4" "$5 ok" "$CODE $(problem_ok "$4")"; chk "$1" "$2" "$3: nothing written" ok "$(unchanged "$6")"; }
notfound() { chk "$1" "$2" "$3: 404 NOT_FOUND" "404 ok" "$CODE $(problem_ok NOT_FOUND)"; chk "$1" "$2" "$3: nothing written" ok "$(unchanged "$4")"; }
# Fault injection (P3-KILL-07): take the runtime role's SELECT on a table away. The trap puts it back if the script dies
# or is interrupted before restore_runtime_select; GRANT is idempotent, so a double restore is harmless.
# Any EXIT/INT/TERM handlers the caller had are saved first and put back on restore.
restore_runtime_select() {
  psqlq "grant select on dokene.$1 to dokene_runtime" >/dev/null
  trap - EXIT INT TERM; eval "${_QA149_PREV_TRAPS:-}"; _QA149_PREV_TRAPS=
}
# bash does not run an EXIT trap installed while the EXIT trap runs, so on a plain exit the caller's EXIT command is run
# explicitly after the restore; on INT/TERM the restore reinstalls it and the following `exit` runs it once.
_qa149_trap_arg() { _QA149_PREV_EXIT=$1; }
revoke_runtime_select() {
  _QA149_PREV_TRAPS=$(trap -p EXIT INT TERM); _QA149_PREV_EXIT=
  local t; t=$(trap -p EXIT); [ -n "$t" ] && eval "_qa149_trap_arg ${t#trap -- }"
  trap "restore_runtime_select $1; eval \"\$_QA149_PREV_EXIT\"" EXIT; trap "restore_runtime_select $1; exit 130" INT; trap "restore_runtime_select $1; exit 143" TERM
  psqlq "revoke select on dokene.$1 from dokene_runtime" >/dev/null
}
# runtime_grants <table>: the runtime role's privileges on a table, sorted ("INSERT,SELECT,UPDATE")
runtime_grants() { psqlq "select string_agg(privilege_type,',' order by privilege_type) from information_schema.role_table_grants where grantee='dokene_runtime' and table_schema='dokene' and table_name='$1'"; }
# Keycloak admin helpers for throwaway identities (P3-PERM-18)
kc_token() { curl -s -d grant_type=password -d client_id=admin-cli -d "username=$KC_BOOTSTRAP_ADMIN_USERNAME" --data-urlencode "password=$KC_BOOTSTRAP_ADMIN_PASSWORD" "http://localhost:${KEYCLOAK_PORT:-8081}/realms/master/protocol/openid-connect/token" | jq -r .access_token; }
kc_create_user() { curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $(kc_token)" -H 'Content-Type: application/json' "http://localhost:${KEYCLOAK_PORT:-8081}/admin/realms/dokene/users" -d "$(jq -nc --arg u "$1" --arg p "$PW" '{username:$u,email:($u+"@dokene.local"),firstName:"QA",lastName:"Temp",enabled:true,emailVerified:true,credentials:[{type:"password",value:$p,temporary:false}]}')"; }
# Every script starts from the send ready defaults of the case table: verified integrations, approved mappings,
# tenant switches on. A previous script that died halfway may have left them otherwise.
ensure_fixtures() {
  local i t
  for t in "$A_OWNER $A $PN_A $WABA_A qa149-token-a" "$B_OWNER $B $PN_B $WABA_B qa149-token-b"; do set -- $t
    i=$(getj $1 "$2" /api/integrations/whatsapp); [ "$(echo "$i" | jq -r '(.enabled and .webhookVerified and .outboundEnabled and .secretRef=="'$5'" and .phoneNumberId=="'$3'" and .businessAccountId=="'$4'")')" = true ] || restore_integration "$@"
    [ "$(getj $1 "$2" /api/templates | jq -r '.items[]|select(.templateIntent=="GENERAL_FOLLOW_UP")|.sendable')" = true ] || restore_template $1 "$2"
  done
}
# template and integration restore after a script edited them (keeps TA sendable for the next script)
restore_template() { # writer tenant
  local v; v=$(tmpl_version "$1" "$2"); stub_set templates approved
  req "$1" "$2" PUT /api/templates/GENERAL_FOLLOW_UP '{"providerTemplateName":"qa149_general_follow_up","language":"es","parameterNames":["customer_name","message_body"],"enabled":true}' -H "If-Match: \"$v\""
  req "$1" "$2" POST /api/templates/GENERAL_FOLLOW_UP/approval-refresh - -H "If-Match: $(etag)"
  echo "  restored template in $2: $CODE sendable=$(echo "$BODY" | jq -r .sendable)"
}
restore_integration() { # manager tenant pn waba ref
  stub_set phones owned
  req "$1" "$2" GET /api/integrations/whatsapp -
  req "$1" "$2" PUT /api/integrations/whatsapp "$(jq -nc --arg p "$3" --arg w "$4" --arg r "$5" '{phoneNumberId:$p,businessAccountId:$w,displayPhoneNumber:"+50600000000",enabled:true,secretRef:$r}')" -H "If-Match: $(etag)"
  [ "$(echo "$BODY" | jq -r .webhookVerified)" = true ] || req "$1" "$2" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: $(etag)"
  [ "$(echo "$BODY" | jq -r .outboundEnabled)" = true ] || req "$1" "$2" PUT /api/integrations/whatsapp/outbound '{"enabled":true}' -H "If-Match: $(etag)"
  echo "  restored integration in $2: verified=$(echo "$BODY" | jq -r .webhookVerified) outbound=$(echo "$BODY" | jq -r .outboundEnabled)"
}
# buy <user> <tenant> <customer> <purchasedAt ISO date> [description]
buy() { req "$1" "$2" POST "/api/customers/$3/purchases" "{\"purchasedAt\":\"$4\",\"description\":\"${5:-Compra QA}\"}" -H "Idempotency-Key: qa149-$RUN-$RANDOM$RANDOM"; }
fpv() { req "$1" "$2" GET "/api/customers/$3/follow-up-policy" -; etag; }
# make_due <user> <tenant> <customer> <contact>: consent + an old purchase, so the follow up is due
make_due() { consent "$1" "$2" "$3" "$4" GRANTED >/dev/null; buy "$1" "$2" "$3" "$(date -u -v-45d +%FT%TZ 2>/dev/null || date -u -d '45 days ago' +%FT%TZ)" >/dev/null; }
# submit <user> <tenant> <customer> <contact> [key] -> CODE/BODY/HDRS; MSG set on 201/200
submit() { # <user> <tenant> <customer> <contact> [key] [If-Match value]
  local key=${5:-qa149-$RUN-$RANDOM$RANDOM} v; v=${6:-$(fpv "$1" "$2" "$3")}
  req "$1" "$2" POST "/api/customers/$3/messages" "$(jq -nc --arg c "$4" '{contactId:$c,action:"GENERAL_CHECK_IN",templateIntent:"GENERAL_FOLLOW_UP",body:"Hola, CANARY-BODY",locale:"es-419",origin:"MANUAL"}')" -H "Idempotency-Key: $key" -H "If-Match: $v"
  MSG=$(echo "$BODY" | jq -r '.messageId // empty' 2>/dev/null)
}
msg_etag() { req "$1" "$2" GET "/api/messages/$3" -; etag; }
approve() { req "$1" "$2" POST "/api/messages/$3/approvals" '{"decision":"APPROVE"}' -H "Idempotency-Key: ${4:-qa149-$RUN-$RANDOM$RANDOM}" -H "If-Match: ${5:-$(msg_etag "$1" "$2" "$3")}"; }
reject() { req "$1" "$2" POST "/api/messages/$3/approvals" '{"decision":"REJECT"}' -H "Idempotency-Key: ${4:-qa149-$RUN-$RANDOM$RANDOM}" -H "If-Match: ${5:-$(msg_etag "$1" "$2" "$3")}"; }
cancel() { req "$1" "$2" POST "/api/messages/$3/cancellations" '{}' -H "Idempotency-Key: ${4:-qa149-$RUN-$RANDOM$RANDOM}" -H "If-Match: ${5:-$(msg_etag "$1" "$2" "$3")}"; }
# resolve <user> <tenant> <message> <outcome> [key] [If-Match]: T12, a member declares an unknown send FAILED
resolve() { req "$1" "$2" POST "/api/messages/$3/resolutions" "{\"outcome\":\"$4\"}" -H "Idempotency-Key: ${5:-qa149-$RUN-$RANDOM$RANDOM}" -H "If-Match: ${6:-$(msg_etag "$1" "$2" "$3")}"; }
send() { req "$1" "$2" POST "/api/messages/$3/sends" - -H "Idempotency-Key: ${4:-qa149-$RUN-$RANDOM$RANDOM}" -H "If-Match: ${5:-$(msg_etag "$1" "$2" "$3")}"; }
# ready_approved <user> <tenant> <name> -> prints "customerId contactId messageId" with the message APPROVED
ready_approved() {
  local ids cid pid; ids=$(mk_customer "$1" "$2" "$3") || return 1; read -r cid pid <<< "$ids"
  make_due "$1" "$2" "$cid" "$pid"; submit "$1" "$2" "$cid" "$pid"
  [ "$CODE" = 201 ] || { echo "submit failed: $CODE $BODY" >&2; return 1; }
  approve "$1" "$2" "$MSG"; [ "$CODE" = 201 ] || { echo "approve failed: $CODE $BODY" >&2; return 1; }
  echo "$cid $pid $MSG"
}
# ready_unknown <user> <tenant> <name> [stub send mode] -> "customerId contactId messageId" with the message SENDING and
# its outcome unknown (MA-UNKNOWN). error500 leaves no provider id; paused keeps one a delivery report can match.
ready_unknown() {
  local ids m prev; ids=$(ready_approved "$1" "$2" "$3") || return 1; m=${ids##* }
  prev=$(cat "$STUBDIR/send" 2>/dev/null || echo accept); stub_set send "${4:-error500}"; send "$1" "$2" "$m"; stub_set send "$prev"
  case $(msg_status "$m") in SENDING/*/t|SENDING/*/true) ;; *) echo "ready_unknown: $CODE $(msg_status "$m")" >&2; return 1;; esac
  echo "$ids"
}
ready_pending() {
  local ids cid pid; ids=$(mk_customer "$1" "$2" "$3") || return 1; read -r cid pid <<< "$ids"
  make_due "$1" "$2" "$cid" "$pid"; submit "$1" "$2" "$cid" "$pid"
  [ "$CODE" = 201 ] || { echo "submit failed: $CODE $BODY" >&2; return 1; }
  echo "$cid $pid $MSG"
}
# integration helpers
integ_etag() { req "$1" "$2" GET /api/integrations/whatsapp -; etag; }
tmpl_version() { getj "$1" "$2" /api/templates | jq -r '.items[]|select(.templateIntent=="GENERAL_FOLLOW_UP")|.version'; }
# webhook signing with the deployment app secret
sign() { printf 'sha256=%s' "$(printf '%s' "$1" | openssl dgst -sha256 -hmac "$2" | awk '{print $NF}')"; }
hook_body() { # <phoneNumberId> <providerMessageId> <status> [timestamp]
  printf '{"object":"whatsapp_business_account","entry":[{"id":"200000000000001","changes":[{"value":{"messaging_product":"whatsapp","metadata":{"display_phone_number":"50600000000","phone_number_id":"%s"},"statuses":[{"id":"%s","status":"%s","timestamp":"%s","recipient_id":"50600000001"}]},"field":"messages"}]}]}' "$1" "$2" "$3" "${4:-$(date +%s)}"
}
hook_post() { # <body> <secret|-> [extra curl args]; "-" posts without a signature
  local b=$1 s=$2; shift 2; printf '%s' "$b" > "$QA_TMP/hook.$$"
  if [ "$s" = "-" ]; then raw -X POST "$BFF/webhooks/whatsapp" -H 'Content-Type: application/json' --data-binary "@$QA_TMP/hook.$$" "$@"
  else raw -X POST "$BFF/webhooks/whatsapp" -H 'Content-Type: application/json' -H "X-Hub-Signature-256: $(sign "$b" "$s")" --data-binary "@$QA_TMP/hook.$$" "$@"; fi
}
