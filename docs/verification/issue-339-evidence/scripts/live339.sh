#!/usr/bin/env bash
# Issue 339: one authorized live Spanish template send. Opens the global window with the default 24h interval (never
# lowered for this run), sends once, then restores tenant off and global off, and verifies off, off and 24h.
# Logs no identifiers. Usage: live339.sh send
#
# Exit status: 0 only when every step succeeded and the window was verified closed (the log then ends with
# LIVEENDONE). When a step fails or the run is interrupted and the window is still verified closed, the status of
# the failed step: 130 for an interrupt, the helper's status for a failed or timed-out send (124 for a timeout), the
# status of a failed opening step (71 if the backend never became healthy), enable or status read. 70 when the window could not be verified closed, whatever
# happened before: the operator must close it by hand (see the CLEANUP FAILED log line).
case "${1:-}" in
  send) HELPER=send339.py ;;
  *) echo 'Usage: live339.sh send' >&2; exit 2 ;;
esac
QA_DIR=/home/stevdrey/qa339
CLEANUP_ATTEMPTS=${CLEANUP_ATTEMPTS:-3}
RETRY_SLEEP=${RETRY_SLEEP:-5}
cd "$QA_DIR" || exit 2
L=$QA_DIR/out/live-339-window.txt; : > "$L"
log() { echo "$(date -u +%FT%TZ) $*" >> "$L"; }
wait_healthy() { for i in $(seq 1 60); do [ "$(docker inspect dokene-qa-run-backend-1 --format '{{.State.Health.Status}}')" = healthy ] && return 0; sleep 5; done; return 1; }
backend_state() { docker exec dokene-qa-run-backend-1 sh -c 'echo outbound=$DOKENE_MESSAGING_OUTBOUND_ENABLED interval=$DOKENE_MESSAGING_MIN_CONTACT_INTERVAL'; }

# Closing the window: every attempt runs all the shutdown steps even when one fails, then reads back the tenant switch
# and the running backend's effective settings. The window counts as closed only when both read back as off and 24h.
# Attempts are bounded across every call (main path and EXIT handler), so a failure is retried, never looped forever.
cleanup=pending   # pending | closed | failed
attempts=0
close_window() {
  [ "$cleanup" = closed ] && return 0
  [ "$cleanup" = failed ] && return 1
  while [ "$attempts" -lt "$CLEANUP_ATTEMPTS" ]; do
    attempts=$((attempts + 1))
    timeout 120 python3 -I $HELPER disable >> "$L" 2>&1; local disable_rc=$?
    ./control.sh outbound false >/dev/null 2>&1; local backend_rc=$?
    wait_healthy; local healthy_rc=$?
    local tenant state
    tenant=$(timeout 60 python3 -I $HELPER tenant 2>/dev/null)
    state=$(backend_state 2>/dev/null)
    log "close attempt $attempts: disable=$disable_rc backend=$backend_rc healthy=$healthy_rc tenant=${tenant:-unknown} backend_state=${state:-unknown}"
    if [ "$tenant" = '{"tenantOutboundEnabled": false}' ] && [ "$state" = 'outbound=false interval=24h' ]; then
      cleanup=closed; log "closed (verified): tenant off, $state"
      return 0
    fi
    [ "$attempts" -lt "$CLEANUP_ATTEMPTS" ] && sleep "$RETRY_SLEEP"
  done
  cleanup=failed
  log "CLEANUP FAILED after $attempts attempts: the send window may still be open. Operator action: turn the tenant switch off (PUT /api/integrations/whatsapp/outbound), run './control.sh outbound false' and confirm 'outbound=false interval=24h' before anything else."
  return 1
}
on_exit() {
  local rc=$?
  close_window || rc=70
  exit $rc
}
trap on_exit EXIT
trap 'log "interrupted"; exit 130' INT TERM HUP

# Every required step keeps its own status: a failure stops the run before the next step (so nothing is sent after a
# failed setup), and the EXIT handler still closes the window. Statuses are captured before anything else runs.
DOKENE_WHATSAPP_TEST_INTERVAL=24h ./control.sh outbound true >/dev/null 2>&1 || { rc=$?; log "open failed: control.sh=$rc"; exit "$rc"; }
wait_healthy || { log "open failed: backend not healthy"; exit 71; }
log "window: $(backend_state)"
timeout 120 python3 -I $HELPER enable >> "$L" 2>&1 || { rc=$?; log "enable failed: $rc"; exit "$rc"; }
timeout 180 python3 -I $HELPER send >> "$L" 2>&1; send_rc=$?
log "send-exit=$send_rc"
close_window || exit 70
[ "$send_rc" -eq 0 ] || exit "$send_rc"
timeout 120 python3 -I $HELPER status >> "$L" 2>&1 || { rc=$?; log "status failed: $rc"; exit "$rc"; }
log LIVEENDONE
