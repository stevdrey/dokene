# shellcheck shell=bash
# ABOUTME: Helpers for manual QA suite #336 (Phase 3 history, webhook outcomes and reconciliation). Reuses the #335
# ABOUTME: helpers; adds QA336 fixtures, signed synthetic Meta callbacks over real HTTP and inbox/event/attempt counters.
# shellcheck disable=SC2034  # sourced library: variables are used by the scripts that source it
#
# Source it from the other scripts. QA_HOME (default ~/qa333) holds the #333 stack and stub; OUTD (default ~/qa336/out)
# holds this suite's results and ids. Never prints tokens, cookies, CSRF values, passwords or the app secret.
# The app secret is the #333 synthetic canary (stack333.sh): it signs callbacks for the local stack only.
QA336_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
export OUTD=${OUTD:-$HOME/qa336/out}
export RESULTS=${RESULTS:-$OUTD/results.tsv}
mkdir -p "$OUTD"
# The shared fixture helpers (next_phone, mkcust, mkmsg) come from qa335.sh with this suite's tag and phone range.
export QA_TAG=QA336 QA_PHONE_PREFIX=+5698336
. "$QA336_DIR/../../issue-335-evidence/scripts/qa335.sh"
. "$QA_HOME/out/ids"            # tenants A and B (fixtures333.sh)
[ -f "$OUTD/ids" ] && . "$OUTD/ids"
# Evidence scripts that send X-CSRF-TOKEN load the protected curl wrapper themselves (scripts/tests/bff-login-test.sh).
. "$(cd "$QA336_DIR/../../../.." && pwd)/scripts/lib/curl-protected.sh"

PNID_A=100000000000001   # tenant A verified mapping (stub phones.list)
PNID_B=100000000000002   # tenant B verified mapping
WH_SECRET_REF=qa333-app-secret

# cancel <message>: cancel an open message as the OWNER (synthetic reason).
cancel() {
  req testuser "$A" GET "/api/messages/$1" -
  req testuser "$A" POST "/api/messages/$1/cancellations" '{"note":"QA336 cancelado sintetico"}' -H "If-Match: $(etag)" -H "Idempotency-Key: $(uuidgen)"
}

pmid() { req testuser "$A" GET "/api/messages/$1" -; jb .providerMessageId; }
# mrow <message>: status/unknown/delivered/read/failure/attempts/version straight from the database (tenant A row).
mrow() { psqlq "select status||'/unknown='||outcome_unknown||'/delivered='||(delivered_at is not null)||'/read='||(read_at is not null)||'/failure='||coalesce(failure_category,'-')||'/v='||version from dokene.outbound_messages where id='$1'"; }
mevents() { psqlq "select count(*) from dokene.outbound_message_events where message_id='$1'"; }
mevtypes() { psqlq "select string_agg(event_type||':'||coalesce(status_from,'-')||'>'||status_to||case when applied then '' else '(not applied)' end, ',' order by sequence_number) from dokene.outbound_message_events where message_id='$1'"; }
inbox_rows() { psqlq "select count(*) from dokene.provider_webhook_events${1:+ where $1}"; }
inbox_for() { psqlq "select coalesce(string_agg(delivery_status||'='||state, ',' order by received_at),'-') from dokene.provider_webhook_events where provider_message_id='$1'"; }
msgs_total() { psqlq 'select count(*) from dokene.outbound_messages'; }
cust_msgs() { psqlq "select count(*) from dokene.outbound_messages where customer_id='$1'"; }

# wh_body <file> <phone-number-id> then one or more "wamid:status[:epoch[:errcode]]" items: a Meta-shaped status
# callback with synthetic values only (no texts, no real numbers).
wh_body() {
  local f=$1 pn=$2; shift 2
  local items='[]' it id st ts ec
  for it in "$@"; do
    IFS=: read -r id st ts ec <<<"$it"
    # wamid ids contain no colon in this stub (wamid.SYNTHETIC-<boot>-<n>), so ':' separates the fields safely.
    items=$(jq -c --arg id "$id" --arg st "$st" --arg ts "${ts:-$(date +%s)}" --arg ec "${ec:-}" \
      '. + [{id:$id,status:$st,timestamp:$ts,recipient_id:"56900000000"} + (if $ec == "" then {} else {errors:[{code:($ec|tonumber),title:"synthetic"}]} end)]' <<<"$items")
  done
  jq -cn --arg pn "$pn" --argjson s "$items" '{object:"whatsapp_business_account",entry:[{id:"200000000000001",changes:[{field:"messages",value:{messaging_product:"whatsapp",metadata:{display_phone_number:"56900000000",phone_number_id:$pn},statuses:$s}}]}]}' > "$f"
}

# wh_sig <file> [secret]: the X-Hub-Signature-256 value for the raw bytes (default: the stack's canary app secret,
# passed to python through the environment, never through an argument).
wh_sig() {
  WH_KEY=${2:-QA333-CANARY-APP-SECRET} python3 -I -c 'import hmac,hashlib,os,sys; print("sha256="+hmac.new(os.environ["WH_KEY"].encode(),open(sys.argv[1],"rb").read(),hashlib.sha256).hexdigest())' "$1"
}

# wh_post <file> [signature|-] [extra curl args]: POST the raw file to the public webhook route with no cookie jar.
# Sets WCODE and WHDRS. "-" sends no signature header. Webhook calls carry no session or CSRF value.
wh_post() {
  local f=$1 sig=${2:-$(wh_sig "$1")}; shift 2 2>/dev/null || shift $#
  # "Expect:" off: for large bodies curl would otherwise print an interim "100 Continue" status first.
  local -a h=(-H 'Content-Type: application/json' -H 'Expect:')
  [ "$sig" != - ] && h+=(-H "X-Hub-Signature-256: $sig")
  local out; out=$(curl -s -i -X POST "${h[@]}" "$@" --data-binary "@$f" "$BFF/webhooks/whatsapp" 2>/dev/null)
  WCODE=$(printf '%s\n' "$out" | head -1 | awk '{print $2}')
  WHDRS=$(printf '%s\n' "$out" | sed '/^\r\{0,1\}$/q')
}
no_cookie() { ! printf '%s\n' "$WHDRS" | grep -qi '^set-cookie:'; }



