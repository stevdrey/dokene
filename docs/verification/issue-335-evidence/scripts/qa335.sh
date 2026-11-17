# shellcheck shell=bash
# ABOUTME: Helpers for manual QA suite #335 (Phase 3 dispatch: safe manual sends, policy gates, duplicate suppression).
# ABOUTME: Reuses the #333/#334 stack helpers; adds disposable customers, approved messages, sends and side-effect counters.
# shellcheck disable=SC2034  # sourced library: variables are used by the scripts that source it
#
# Source it from the other scripts. QA_HOME (default ~/qa333) holds the #333 stack, stub and ids; OUTD (default
# ~/qa335/out) holds this suite's results and ids. Never prints tokens, cookies, CSRF values or passwords.
QA335_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
export OUTD=${OUTD:-$HOME/qa335/out}
export RESULTS=${RESULTS:-$OUTD/results.tsv}
mkdir -p "$OUTD"
. "$QA335_DIR/../../issue-334-evidence/scripts/qa334.sh"
# Window scripts from #334 restart through this suite's stack wrapper (adds the W2 policy overrides, see stack335.sh).
export STACK333=$QA335_DIR/stack335.sh
STUBDIR=$QA_HOME/stub
[ -f "$OUTD/ids" ] && . "$OUTD/ids"

ok200() { [ "$CODE" = 200 ] && echo PASS || echo FAIL; }
jb() { printf '%s' "$BODY" | jq -r "$1" 2>/dev/null; }
code() { jb '.code // empty'; }
setid() { printf '%s=%s\n' "$1" "$2" >> "$OUTD/ids"; printf -v "$1" '%s' "$2"; }
# stub_mode <send|templates|phones> <mode>: the stub reads its control files on every request (no restart).
stub_mode() { printf '%s' "$2" > "$STUBDIR/$1"; }
stub_delay() { if [ "${1:-0}" = 0 ]; then rm -f "$STUBDIR/delay.ms"; else printf '%s' "$1" > "$STUBDIR/delay.ms"; fi; }
stub_reset() { stub_mode send accept; stub_mode templates approved; stub_mode phones owned; stub_delay 0; }
attempts() { psqlq "select count(*) from dokene.outbound_send_attempts where message_id='$1'"; }
attempts_all() { psqlq 'select count(*) from dokene.outbound_send_attempts'; }
events() { req testuser "$A" GET "/api/messages/$1/events" -; jb '[.events[].type]|join(",")'; }
mstate() { req testuser "$A" GET "/api/messages/$1" -; jb '"\(.status)/unknown=\(.outcomeUnknown)/attempts=\(.attempts|length)"'; }

# mkcust <var> <name>: disposable tenant A customer: CL test-range phone, WhatsApp consent GRANTED, purchase 40 days
# ago, due today. Sets <var> (customer id) and <var>_C (contact id) and records both in $OUTD/ids.
mkcust() {
  local v=$1 name=$2 n phone cid pid tz
  n=$(( $(grep -c '_C=' "$OUTD/ids" 2>/dev/null || echo 0) + 1 ))
  phone=$(printf '+5698335%04d' "$(( n + ${QA335_PHONE_BASE:-0} ))")
  req testuser "$A" POST /api/customers "$(jq -cn --arg n "$name" --arg p "$phone" '{displayName:$n,phones:[{number:$p,region:"CL",primary:true}]}')"
  [ "$CODE" = 201 ] || { echo "create $name: http=$CODE $(code)" >&2; return 1; }
  cid=$(jb .id); pid=$(jb '.phones[0].id')
  req testuser "$A" GET "/api/customers/$cid/contact-policy" -
  req testuser "$A" PUT "/api/customers/$cid/contacts/$pid/consents/WHATSAPP" '{"status":"GRANTED","source":"CUSTOMER_VERBAL"}' -H "If-Match: $(etag)"
  [ "$CODE" = 200 ] || { echo "consent $name: http=$CODE" >&2; return 1; }
  req testuser "$A" POST "/api/customers/$cid/purchases" "$(jq -cn --arg t "$(date -u -d '-40 days' +%FT%TZ)" '{purchasedAt:$t,description:"Compra sintetica QA335"}')" -H "Idempotency-Key: qa335-p-$cid"
  req testuser "$A" GET "/api/customers/$cid/follow-up-policy" -; tz=$(jb '.timeZone // "UTC"')
  req testuser "$A" PUT "/api/customers/$cid/follow-up-policy" "$(jq -cn --arg d "$(TZ=$tz date +%F)" '{cadenceDays:null,explicitNextDate:$d,snoozedUntil:null}')" -H "If-Match: $(etag)"
  [ "$CODE" = 200 ] || { echo "due $name: http=$CODE" >&2; return 1; }
  setid "$v" "$cid"; setid "${v}_C" "$pid"
}

# due_today <customer>: reschedule the follow-up to today (a send moves it 30 days out).
due_today() {
  local tz; req testuser "$A" GET "/api/customers/$1/follow-up-policy" -; tz=$(jb '.timeZone // "UTC"')
  req testuser "$A" PUT "/api/customers/$1/follow-up-policy" "$(jq -cn --arg d "$(TZ=$tz date +%F)" '{cadenceDays:null,explicitNextDate:$d,snoozedUntil:null}')" -H "If-Match: $(etag)"
}

# mkmsg <var> <user> <customer> <contact> [approve]: submit a manual REPEAT_PURCHASE message (synthetic text) and,
# with "approve", approve the exact template preview revision as the same user. Sets <var>; returns nonzero on failure.
mkmsg() {
  local v=$1 u=$2 c=$3 k=$4 mid pv
  req "$u" "$A" GET "/api/customers/$c/follow-up-policy" -; pv=$(etag)
  req "$u" "$A" POST "/api/customers/$c/messages" "$(jq -nc --arg c "$k" --arg b "QA335 seguimiento sintetico $v" --arg a "${MSG_ACTION:-REPEAT_PURCHASE_FOLLOW_UP}" --arg i "${MSG_INTENT:-REPEAT_PURCHASE}" '{contactId:$c,action:$a,templateIntent:$i,body:$b,locale:"es-419",origin:"MANUAL"}')" -H "If-Match: $pv" -H "Idempotency-Key: $(uuidgen)"
  [ "$CODE" = 201 ] || { echo "submit $v: http=$CODE $(code)" >&2; return 1; }
  mid=$(jb .messageId); setid "$v" "$mid"
  [ "${5:-}" = approve ] || return 0
  req "$u" "$A" GET "/api/messages/$mid" -
  local rev; rev=$(jb .template.revision)
  req "$u" "$A" POST "/api/messages/$mid/approvals" "$(jq -nc --arg r "$rev" '{decision:"APPROVE",templateRevision:$r}')" -H "If-Match: $(etag)" -H "Idempotency-Key: $(uuidgen)"
  [ "$CODE" = 200 ] || [ "$CODE" = 201 ] || { echo "approve $v: http=$CODE $(code)" >&2; return 1; }
  [ "$(jb .status)" = APPROVED ]
}

# send <user> <message> [key]: one manual send at the message's current version. Sets CODE/BODY like req.
send() {
  local k=${3:-$(uuidgen)}
  req "$1" "$A" GET "/api/messages/$2" -
  req "$1" "$A" POST "/api/messages/$2/sends" - -H "If-Match: $(etag)" -H "Idempotency-Key: $k"
}

# refused <expected-code> <message> <stub-before> <label> [case]: record a send refusal check: the expected HTTP 409/403
# code, no attempt row for the message, no new stub send, message still APPROVED.
refused() {
  local want=$1 m=$2 s0=$3 label=$4 c=${5:-$C} got st a s
  got=$(code); st=$(jb .status); a=$(attempts "$m"); s=$(stub_sends)
  req testuser "$A" GET "/api/messages/$m" -
  rec "$c" "$label: refused $want, 0 attempts, 0 provider requests, still APPROVED" \
    "$([ "$got" = "$want" ] && [ "$a" = 0 ] && [ "$s" = "$s0" ] && [ "$(jb .status)" = APPROVED ] && echo PASS || echo FAIL)" \
    "code=$got attempts=$a stub_delta=$((s - s0)) status=$(jb .status) msg=${m:0:8}"
  : "$st"
}

# assert_baseline_policy: the backend runs with the default send policy (no W2 overrides in its environment).
assert_baseline_policy() {
  local e; e=$(docker compose -f "$WT/compose.yaml" exec -T backend sh -c 'env | grep -E "^DOKENE_MESSAGING_(MIN_CONTACT_INTERVAL|RECOVERY_|SEND_TIMEOUT)" || true')
  [ -z "$e" ] || { echo "policy overrides present: $(printf '%s' "$e" | cut -d= -f1 | tr '\n' ' ')" >&2; return 1; }
}
