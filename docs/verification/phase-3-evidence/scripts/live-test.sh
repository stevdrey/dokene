#!/usr/bin/env bash
# Offline tests for live.sh: no Meta, no Docker, no credentials. Each case runs live.sh in a temporary QA directory
# with stubbed control.sh, python3 and docker that keep the tenant switch, the global switch and the interval in
# files, and checks the exit status, the final state and the log. Usage: live-test.sh
here=$(cd "$(dirname "$0")" && pwd)
fails=0

run_case() { # name expected_rc expected_final_state log_must_contain log_must_not_contain sends [env assignments...]
  local name=$1 want_rc=$2 want_state=$3 want_log=$4 deny_log=$5 want_sends=$6; shift 6
  local dir; dir=$(mktemp -d); mkdir -p "$dir/out" "$dir/bin" "$dir/state"
  echo false > "$dir/state/tenant"; echo false > "$dir/state/global"; echo 24h > "$dir/state/interval"
  cp "$here/live.sh" "$dir/live.sh"
  # control.sh outbound true|false: recreates the "backend" with that global switch and the interval it was given.
  cat > "$dir/control.sh" <<'EOF'
#!/usr/bin/env bash
s=$(dirname "$0")/state
if [ "$1 $2" = "outbound false" ] && [ -n "$FAIL_BACKEND" ]; then
  n=$(cat "$s/backend_failures" 2>/dev/null || echo 0); echo $((n + 1)) > "$s/backend_failures"
  if [ "$FAIL_BACKEND" = always ] || [ "$n" -lt "$FAIL_BACKEND" ]; then exit 73; fi
fi
echo "$2" > "$s/global"; echo "${DOKENE_WHATSAPP_TEST_INTERVAL:-24h}" > "$s/interval"
EOF
  # python3 -I <helper> <action>: the send helpers and check140.py against the state files.
  cat > "$dir/bin/python3" <<'EOF'
#!/usr/bin/env bash
s=$STATE_DIR
case "$3" in
  enable)
    if [ -n "$FAIL_ENABLE" ]; then echo 'simulated tenant-enable failure' >&2; exit "$FAIL_ENABLE"; fi
    echo true > "$s/tenant"; echo '{"tenantOutboundEnabled": true}' ;;
  disable)
    n=$(cat "$s/disable_failures" 2>/dev/null || echo 0); echo $((n + 1)) > "$s/disable_failures"
    if [ -n "$FAIL_DISABLE" ] && { [ "$FAIL_DISABLE" = always ] || [ "$n" -lt "$FAIL_DISABLE" ]; }; then
      echo 'simulated tenant-disable HTTP failure' >&2; exit 42; fi
    echo false > "$s/tenant"; echo '{"tenantOutboundEnabled": false}' ;;
  tenant) echo "{\"tenantOutboundEnabled\": $(cat "$s/tenant")}" ;;
  send)
    echo sent >> "$s/sends"
    if [ -n "$FAIL_SEND" ]; then echo 'simulated send failure' >&2; exit "$FAIL_SEND"; fi
    sleep "${SEND_SLEEP:-0}"; echo '{"status": "SENT", "attempts": 1}' ;;
  status)
    if [ -n "$FAIL_STATUS" ]; then echo 'simulated status failure' >&2; exit "$FAIL_STATUS"; fi
    echo '{"status": "SENT"}' ;;
  *) [ "$2" = check140.py ] && echo '{"templateIntent": "GENERAL_FOLLOW_UP", "sendable": true, "language": "es"}' ;;
esac
EOF
  # docker inspect/exec: the backend is always healthy and reports the current global switch and interval.
  cat > "$dir/bin/docker" <<'EOF'
#!/usr/bin/env bash
s=$STATE_DIR
case "$1" in
  inspect) echo healthy ;;
  exec) echo "outbound=$(cat "$s/global") interval=$(cat "$s/interval")" ;;
esac
EOF
  chmod +x "$dir/live.sh" "$dir/control.sh" "$dir/bin/python3" "$dir/bin/docker"
  local rc
  if [ "$name" = interrupted ]; then
    env PATH="$dir/bin:$PATH" STATE_DIR="$dir/state" QA_DIR="$dir" RETRY_SLEEP=0 SEND_SLEEP=3 "$@" "$dir/live.sh" es &
    local pid=$!; sleep 1; kill -TERM "$pid"; wait "$pid"; rc=$?
  else
    env PATH="$dir/bin:$PATH" STATE_DIR="$dir/state" QA_DIR="$dir" RETRY_SLEEP=0 "$@" "$dir/live.sh" es; rc=$?
  fi
  local state="tenant=$(cat "$dir/state/tenant") global=$(cat "$dir/state/global") interval=$(cat "$dir/state/interval")"
  local sends=0; [ -f "$dir/state/sends" ] && sends=$(wc -l < "$dir/state/sends"); sends=${sends// /}
  local denied=0; [ -n "$deny_log" ] && grep -q -- "$deny_log" "$dir/out/live-140-es.txt" && denied=1
  if [ "$rc" = "$want_rc" ] && [ "$state" = "$want_state" ] && grep -q -- "$want_log" "$dir/out/live-140-es.txt" \
      && [ "$denied" = 0 ] && [ "$sends" = "$want_sends" ]; then
    echo "PASS  $name (exit $rc, $state)"
  else
    echo "FAIL  $name: exit $rc (want $want_rc), $state (want $want_state), log has '$want_log': $(grep -c -- "$want_log" "$dir/out/live-140-es.txt"), forbidden '$deny_log' present: $denied, sends $sends (want $want_sends)"
    sed 's/^/      /' "$dir/out/live-140-es.txt"; fails=$((fails + 1))
  fi
  rm -rf "$dir"
}

# name                       exit  final state                                must log            must not log   sends
run_case success                0   'tenant=false global=false interval=24h' 'closed (verified)' ''             1
run_case interrupted          130   'tenant=false global=false interval=24h' 'closed (verified)' 'LIVEENDONE'   1
run_case transient-disable-failure 0 'tenant=false global=false interval=24h' 'closed (verified)' ''            1 FAIL_DISABLE=1
run_case tenant-disable-failure 70  'tenant=true global=false interval=24h'  'CLEANUP FAILED'    'LIVEENDONE'   1 FAIL_DISABLE=always
run_case backend-recreation-failure 70 'tenant=false global=true interval=1m' 'CLEANUP FAILED'   'LIVEENDONE'   1 FAIL_BACKEND=always
# The send's own status survives a verified closure (the timeout status 124 included); nothing reports success.
run_case send-failure          17   'tenant=false global=false interval=24h' 'closed (verified)' 'LIVEENDONE'   1 FAIL_SEND=17
run_case send-timeout         124   'tenant=false global=false interval=24h' 'closed (verified)' 'LIVEENDONE'   1 FAIL_SEND=124
# A failed setup step stops before the send and still closes the window.
run_case enable-failure          5  'tenant=false global=false interval=24h' 'closed (verified)' 'LIVEENDONE'   0 FAIL_ENABLE=5
# A failed read-only status after a verified closure is still a failed run.
run_case status-failure          9  'tenant=false global=false interval=24h' 'status failed'     'LIVEENDONE'   1 FAIL_STATUS=9
echo "SUMMARY live-test fails=$fails"
[ "$fails" = 0 ]
