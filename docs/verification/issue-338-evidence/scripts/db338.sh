#!/usr/bin/env bash
# ABOUTME: Suite #338: read-only state helper for the browser scripts. Prints one line per query; never prints secrets.
#   db338.sh msg <message-id>       -> status/vVERSION/approvals=N/attempts=N/stub=N
#   db338.sh ids                    -> the suite ids file as KEY=VALUE lines
#   db338.sh sql "<select ...>"     -> raw psql -At output
set -uo pipefail
. "$(dirname "$0")/qa338.sh" >/dev/null 2>&1
case ${1:-} in
  msg) psqlq "select status||'/v'||version||'/approvals='||(select count(*) from dokene.outbound_message_events e where e.message_id=m.id and e.event_type='APPROVED')||'/attempts='||(select count(*) from dokene.outbound_send_attempts a where a.message_id=m.id) from dokene.outbound_messages m where id='$2'" | tr -d '\n'; echo "/stub=$(stub_sends)" ;;
  ids) cat "$OUTD/ids" ;;
  # Read-only by construction: the session refuses any write before the query runs.
  sql) docker compose -f "$WT/compose.yaml" exec -T postgres psql -U "$DOKENE_DB_BOOTSTRAP_USERNAME" -d "$DOKENE_DB_NAME" -At \
         -v ON_ERROR_STOP=1 -c 'SET default_transaction_read_only = on' -c "$2" | sed '1{/^SET$/d}' ;;
  *) echo "usage: $0 msg <id> | ids | sql <query>" >&2; exit 2 ;;
esac
