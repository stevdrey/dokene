#!/usr/bin/env bash
# Offline tests for live.sh: no Meta, no Docker, no credentials. Each case runs live.sh in a temporary QA directory
# with stubbed control.sh, python3 and docker that keep the tenant switch, the global switch and the interval in
# files, and checks the exit status, the final state and the log. Usage: live-test.sh
here=$(cd "$(dirname "$0")" && pwd)
fails=0

run_case() { # name expected_rc expected_final_state log_must_contain [env assignments...]
  local name=$1 want_rc=$2 want_state=$3 want_log=$4; shift 4
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
  enable) echo true > "$s/tenant"; echo '{"tenantOutboundEnabled": true}' ;;
  disable)
    n=$(cat "$s/disable_failures" 2>/dev/null || echo 0); echo $((n + 1)) > "$s/disable_failures"
    if [ -n "$FAIL_DISABLE" ] && { [ "$FAIL_DISABLE" = always ] || [ "$n" -lt "$FAIL_DISABLE" ]; }; then
      echo 'simulated tenant-disable HTTP failure' >&2; exit 42; fi
    echo false > "$s/tenant"; echo '{"tenantOutboundEnabled": false}' ;;
  tenant) echo "{\"tenantOutboundEnabled\": $(cat "$s/tenant")}" ;;
  send) sleep "${SEND_SLEEP:-0}"; echo '{"status": "SENT", "attempts": 1}' ;;
  status) echo '{"status": "SENT"}' ;;
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
  if [ "$rc" = "$want_rc" ] && [ "$state" = "$want_state" ] && grep -q -- "$want_log" "$dir/out/live-140-es.txt"; then
    echo "PASS  $name (exit $rc, $state)"
  else
    echo "FAIL  $name: exit $rc (want $want_rc), $state (want $want_state), log has '$want_log': $(grep -c -- "$want_log" "$dir/out/live-140-es.txt")"
    sed 's/^/      /' "$dir/out/live-140-es.txt"; fails=$((fails + 1))
  fi
  rm -rf "$dir"
}

run_case success 0 'tenant=false global=false interval=24h' 'closed (verified)'
run_case interrupted 130 'tenant=false global=false interval=24h' 'closed (verified)'
run_case transient-disable-failure 0 'tenant=false global=false interval=24h' 'closed (verified)' FAIL_DISABLE=1
run_case tenant-disable-failure 70 'tenant=true global=false interval=24h' 'CLEANUP FAILED' FAIL_DISABLE=always
run_case backend-recreation-failure 70 'tenant=false global=true interval=1m' 'CLEANUP FAILED' FAIL_BACKEND=always
echo "SUMMARY live-test fails=$fails"
[ "$fails" = 0 ]
