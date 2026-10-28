#!/usr/bin/env bash
# Issue 140: one authorized live template send, Spanish (es) or English (en). Opens the global window with a 1m
# interval, sends once, then restores tenant off, global off, interval 24h and the Spanish GENERAL_FOLLOW_UP mapping.
# Logs no identifiers. Usage: live.sh es|en
case "${1:-}" in
  es) HELPER=send140.py ;;
  en) HELPER=send140en.py ;;
  *) echo 'Usage: live.sh es|en' >&2; exit 2 ;;
esac
cd /home/stevdrey/qa140
L=/home/stevdrey/qa140/out/live-140-$1.txt; : > $L
log() { echo "$(date -u +%FT%TZ) $*" >> $L; }
wait_healthy() { for i in $(seq 1 60); do [ "$(docker inspect dokene-qa-run-backend-1 --format '{{.State.Health.Status}}')" = healthy ] && return 0; sleep 5; done; return 1; }
# Close the window however the run ends (normal exit, error, Ctrl-C, kill): tenant switch off, the backend back on
# the global switch off and the default 24h interval, and GENERAL_FOLLOW_UP mapped to the Spanish template again
# (check140.py maps it and refreshes its approval). Registered before the window opens and run at most once.
closed=0
close_window() {
  [ "$closed" = 1 ] && return; closed=1
  timeout 120 python3 -I $HELPER disable >> $L 2>&1
  ./control.sh outbound false >/dev/null 2>&1; wait_healthy
  log "closed: $(docker exec dokene-qa-run-backend-1 sh -c 'echo outbound=$DOKENE_MESSAGING_OUTBOUND_ENABLED interval=$DOKENE_MESSAGING_MIN_CONTACT_INTERVAL')"
  timeout 120 python3 -I check140.py >> $L 2>&1
}
trap close_window EXIT
trap 'log "interrupted"; exit 130' INT TERM HUP
DOKENE_WHATSAPP_TEST_INTERVAL=1m ./control.sh outbound true >/dev/null 2>&1; wait_healthy
log "window: $(docker exec dokene-qa-run-backend-1 sh -c 'echo outbound=$DOKENE_MESSAGING_OUTBOUND_ENABLED interval=$DOKENE_MESSAGING_MIN_CONTACT_INTERVAL')"
timeout 120 python3 -I $HELPER enable >> $L 2>&1
timeout 180 python3 -I $HELPER send >> $L 2>&1; log "send-exit=$?"
close_window
timeout 120 python3 -I $HELPER status >> $L 2>&1
log LIVEENDONE
