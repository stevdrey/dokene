# shellcheck shell=bash
# ABOUTME: Helpers for manual QA suite #337 (Phase 3 role, tenant and browser security boundaries). Reuses the #335/#336
# ABOUTME: helpers; adds audit and side-effect counters and tenant-B fixtures. Synthetic data only.
# shellcheck disable=SC2034  # sourced library: variables are used by the scripts that source it
#
# Source it from the other scripts. QA_HOME (default ~/qa333) holds the #333 stack and stub; OUTD (default ~/qa337/out)
# holds this suite's results and ids. Never prints tokens, cookies, CSRF values, passwords or secrets.
QA337_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# A caller may point OUTD/RESULTS elsewhere to isolate a run; keep those values across the inherited helpers.
QA337_OUTD=${OUTD:-$HOME/qa337/out}
QA337_RESULTS=${RESULTS:-$QA337_OUTD/results.tsv}
export OUTD=$QA337_OUTD RESULTS=$QA337_RESULTS
mkdir -p "$OUTD"
export QA_TAG=QA337 QA_PHONE_PREFIX=+5698337
# qa336.sh brings qa335.sh (fixtures, send, refused), the protected curl wrapper and the signed-callback helpers.
. "$QA337_DIR/../../issue-336-evidence/scripts/qa336.sh"
# qa336.sh sets its own tag, phone range and OUTD default: restore this suite's values.
export OUTD=$QA337_OUTD RESULTS=$QA337_RESULTS QA_TAG=QA337 QA_PHONE_PREFIX=+5698337
mkdir -p "$OUTD"
[ -f "$OUTD/ids" ] && . "$OUTD/ids"
# Every side-effect check compares stub counts: without the log they would compare two empty strings, so stop here.
[ -f "$QA_HOME/stub/requests.log" ] || { echo "stub log $QA_HOME/stub/requests.log missing" >&2; exit 1; }

# Side-effect counters: audit rows, denial rows, messages, attempts, approvals, stub sends, AI invocations.
audit_n() { psqlq "select count(*) from dokene.audit_events${1:+ where $1}"; }
deny_n() { audit_n "event_type='AUTHORIZATION_DENIED'"; }
last_deny() { psqlq "select coalesce(permission,'-')||'|'||coalesce(denial_reason,'-') from dokene.audit_events where event_type='AUTHORIZATION_DENIED' order by occurred_at desc, id desc limit 1"; }
ai_n() { audit_n "event_type='AI_INVOCATION_OUTCOME'"; }
# snap: one string of every state counter a refused request must leave unchanged.
snap() { psqlq "select (select count(*) from dokene.outbound_messages)||'/'||(select count(*) from dokene.outbound_message_events)||'/'||(select count(*) from dokene.outbound_send_attempts)||'/'||(select count(*) from dokene.customers)||'/'||(select count(*) from dokene.audit_events where event_type<>'AUTHORIZATION_DENIED')"; echo "/stub=$(stub_sends)"; }
snap1() { snap | tr -d '\n'; }
# mrowv <message>: status/version from the database (any tenant).
mrowv() { psqlq "select status||'/v'||version||'/'||md5(coalesce(body,'')) from dokene.outbound_messages where id='$1'"; }
# stub_all: every request the Graph stub logged (sends, template and phone reads), not only sends.
stub_all() { local log=$QA_HOME/stub/requests.log; [ -f "$log" ] || return 3; wc -l < "$log"; }

# denied <case> <user> <tenant> <METHOD> <path> <json|-> <permission> <label> [curl args]: the call is refused with 403,
# writes exactly one AUTHORIZATION_DENIED row naming <permission>, and changes no other state or stub counter.
denied() {
  local c=$1 u=$2 t=$3 m=$4 p=$5 d=$6 perm=$7 label=$8; shift 8
  local s0 d0 s1 d1 last st0
  s0=$(snap1); st0=$(stub_all); d0=$(deny_n)
  req "$u" "$t" "$m" "$p" "$d" "$@"
  d1=$(deny_n); s1=$(snap1); last=$(last_deny)
  rec "$c" "$label: 403, one denial row naming $perm, nothing else written" \
    "$( { [ "$CODE" = 403 ] && [ $((d1 - d0)) = 1 ] && [ "${last%%|*}" = "$perm" ] && [ "$s1" = "$s0" ] && [ "$(stub_all)" = "$st0" ]; } && echo PASS || echo FAIL)" \
    "http=$CODE code=$(code) denials=+$((d1 - d0)) last=$last state_same=$([ "$s1" = "$s0" ] && echo y || echo n) stub_delta=$(( $(stub_all) - st0 ))"
}
# passes_auth <case> <user> <tenant> <METHOD> <path> <json|-> <want-http> <want-code> <label> [curl args]: the role is
# authorized (no denial row) and the call stops at the later precondition/gate check, writing nothing.
passes_auth() {
  local c=$1 u=$2 t=$3 m=$4 p=$5 d=$6 wh=$7 wc=$8 label=$9; shift 9
  local s0 d0 s1 d1 st0
  s0=$(snap1); st0=$(stub_all); d0=$(deny_n)
  req "$u" "$t" "$m" "$p" "$d" "$@"
  d1=$(deny_n); s1=$(snap1)
  rec "$c" "$label: authorized, stops at $wh ${wc:-}, nothing written" \
    "$( { [ "$CODE" = "$wh" ] && { [ -z "$wc" ] || [ "$(code)" = "$wc" ]; } && [ "$d1" = "$d0" ] && [ "$s1" = "$s0" ] && [ "$(stub_all)" = "$st0" ]; } && echo PASS || echo FAIL)" \
    "http=$CODE code=$(code) denials=+$((d1 - d0)) state_same=$([ "$s1" = "$s0" ] && echo y || echo n) stub_delta=$(( $(stub_all) - st0 ))"
}
