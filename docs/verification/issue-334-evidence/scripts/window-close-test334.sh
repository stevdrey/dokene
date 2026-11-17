#!/usr/bin/env bash
# ABOUTME: Offline test for window-close334.sh: runs the real script against a fake stack (no Docker, no HTTP) and checks
# ABOUTME: normal close, injected restart and verification failures (fail closed, state kept), concurrency and no-op.
# shellcheck disable=SC2034  # rc, r1 and r2 are read inside the strings that ok() evaluates
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
T=$(mktemp -d); trap 'pkill -P $$ 2>/dev/null; rm -rf "$T"' EXIT
FAILS=0
ok() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; FAILS=$((FAILS + 1)); fi; }
cp "$HERE/window-close334.sh" "$T/"
# Fake helpers: the stack is a few files under $T/s (global, tenant, backend, restart mode, restart count).
cat > "$T/qa334.sh" <<EOF
S=$T/s; OUTD=$T/out; A=tenant-a; QA_FAILS=0; mkdir -p "\$OUTD"
STACK333=$T/stack333.sh
rec() { printf '%s\t%s\t%s\t-\t%s\n' "\$1" "\$2" "\$3" "\$4" >> "\$OUTD/results.tsv"; }
assert_safe_stack() { [ "\$(cat \$S/backend)" = up ] && [ "\$(cat \$S/global)" = "\$1" ]; }
qa_login() { [ "\$(cat \$S/backend)" = up ]; }
integ() { :; }; etag() { echo '"1"'; }
req() { if [ "\$(cat \$S/backend)" = up ]; then CODE=200; echo false > \$S/tenant; else CODE=000; fi; }
tenant_state() { echo "true/true/\$(cat \$S/tenant)"; }
stub_sends() { echo 0; }
stop_backend() { echo down > \$S/backend; }
backend_running() { [ "\$(cat \$S/backend)" = up ]; }
EOF
cat > "$T/stack333.sh" <<EOF
#!/usr/bin/env bash
S=$T/s; echo \$((\$(cat \$S/restarts) + 1)) > \$S/restarts; sleep "\$(cat \$S/delay)"
case "\$(cat \$S/mode)" in
  fail) exit 1 ;;
  stuck-on) echo up > \$S/backend; echo true > \$S/global ;;
  *) echo up > \$S/backend; [ "\$QA333_OUTBOUND" = false ] && echo false > \$S/global || echo true > \$S/global ;;
esac
EOF
# open_window <mode>: global ON, tenant ON, token set, a live watchdog process recorded.
open_window() {
  mkdir -p "$T/s" "$T/out"; echo true > "$T/s/global"; echo true > "$T/s/tenant"; echo up > "$T/s/backend"
  echo 0 > "$T/s/restarts"; echo "$1" > "$T/s/mode"; echo 0 > "$T/s/delay"; : > "$T/out/results.tsv"
  echo "W-test-token" > "$T/out/window.open"
  setsid bash -c 'sleep 300' </dev/null >/dev/null 2>&1 & WD=$!; echo "$WD" > "$T/out/window.watchdog"
}
# The watchdog process recorded by open_window (saved PID, not the file, which the close may delete).
alive() { [ -n "${WD:-}" ] && kill -0 "$WD" 2>/dev/null; }
close() { bash "$T/window-close334.sh" "$@" >/dev/null 2>&1; }

# 1. normal close: verified OFF, then window state cleared and the watchdog stopped
open_window ok; close manual; rc=$?
ok "normal: exit 0" '[ $rc = 0 ]'
ok "normal: global off, tenant off" '[ "$(cat $T/s/global)/$(cat $T/s/tenant)" = false/false ]'
ok "normal: token and watchdog pid cleared" '[ ! -e $T/out/window.open ] && [ ! -e $T/out/window.watchdog ]'
ok "normal: watchdog process stopped" '! alive'
ok "normal: one restart" '[ "$(cat $T/s/restarts)" = 1 ]'

# 2. injected restart failure: two attempts, then fail closed with the window state and watchdog kept
open_window fail; close manual; rc=$?
ok "restart failure: exit 4" '[ $rc = 4 ]'
ok "restart failure: two attempts" '[ "$(cat $T/s/restarts)" = 2 ]'
ok "restart failure: backend stopped (fail closed)" '[ "$(cat $T/s/backend)" = down ]'
ok "restart failure: token kept" '[ -e $T/out/window.open ]'
ok "restart failure: watchdog still armed" 'alive'
ok "restart failure: FAIL recorded" 'grep -q "CLOSE NOT VERIFIED" $T/out/results.tsv'
echo ok > "$T/s/mode"; close watchdog; rc=$?
ok "retry after recovery: exit 0 and state cleared" '[ $rc = 0 ] && [ ! -e $T/out/window.open ] && [ "$(cat $T/s/global)" = false ]'

# 3. injected verification failure: restart succeeds but the global switch stays ON
open_window stuck-on; close manual; rc=$?
ok "stuck ON: exit 4, backend stopped, token kept" '[ $rc = 4 ] && [ "$(cat $T/s/backend)" = down ] && [ -e $T/out/window.open ]'

# 4. concurrent manual close and watchdog: serialized, one restart, the second sees the window closed
open_window ok; echo 1 > "$T/s/delay"
close manual & P1=$!; sleep 0.2; close watchdog & P2=$!; wait $P1; r1=$?; wait $P2; r2=$?
ok "concurrent: both exit 0" '[ $r1 = 0 ] && [ $r2 = 0 ]'
ok "concurrent: a single restart" '[ "$(cat $T/s/restarts)" = 1 ]'

# 5. watchdog with no open window: no-op
open_window ok; rm -f "$T/out/window.open"; close watchdog; rc=$?
ok "watchdog no-op: exit 0, no restart" '[ $rc = 0 ] && [ "$(cat $T/s/restarts)" = 0 ]'

[ $FAILS -eq 0 ] && echo "all window-close tests passed" || { echo "$FAILS window-close test(s) failed"; exit 1; }
