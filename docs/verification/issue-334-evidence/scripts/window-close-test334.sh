#!/usr/bin/env bash
# ABOUTME: Offline test for window-close334.sh and window-open334.sh: runs the real scripts against a fake stack (no
# ABOUTME: Docker, no HTTP): close safety, injected failures, close/close and open/close races, lock release and no-op.
# shellcheck disable=SC2034  # rc, r1 and r2 are read inside the strings that ok() evaluates
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
T=$(mktemp -d); trap 'pkill -P $$ 2>/dev/null; rm -rf "$T"' EXIT
FAILS=0
ok() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; FAILS=$((FAILS + 1)); fi; }
cp "$HERE/window-close334.sh" "$HERE/window-open334.sh" "$T/"
# Fake helpers: the stack is a few files under $T/s (global, tenant, backend, restart mode, restart count).
cat > "$T/qa334.sh" <<EOF
S=$T/s; OUTD=$T/out; A=tenant-a; QA_FAILS=0; mkdir -p "\$OUTD"
STACK333=$T/stack333.sh
rec() { printf '%s\t%s\t%s\t-\t%s\n' "\$1" "\$2" "\$3" "\$4" >> "\$OUTD/results.tsv"; }
assert_safe_stack() { [ "\$(cat \$S/backend)" = up ] && [ "\$(cat \$S/global)" = "\$1" ]; }
qa_login() { [ "\$(cat \$S/backend)" = up ]; }
integ() { :; }; etag() { echo '"1"'; }
req() { if [ "\$(cat \$S/backend)" = up ]; then CODE=200; case "\$5" in *true*) echo true;; *) echo false;; esac > \$S/tenant; else CODE=000; fi; }
qa_finish() { [ "\$QA_FAILS" -eq 0 ] || exit 1; exit 0; }
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
close() { timeout 20 bash "$T/window-close334.sh" "$@" >/dev/null 2>&1; }
opn() { WINDOW_TTL=300 timeout 20 bash "$T/window-open334.sh" "$@" >/dev/null 2>&1; }
# clean_stack <mode>: global OFF, tenant OFF, no window state.
clean_stack() {
  mkdir -p "$T/s" "$T/out"; echo false > "$T/s/global"; echo false > "$T/s/tenant"; echo up > "$T/s/backend"
  echo 0 > "$T/s/restarts"; echo "$1" > "$T/s/mode"; echo 0 > "$T/s/delay"; : > "$T/out/results.tsv"
  rm -f "$T/out/window.open" "$T/out/window.watchdog"; WD=
}
# guarded: the safety invariant. A global-ON stack always has a window token and a live watchdog.
guarded() {
  [ "$(cat "$T/s/global")" = true ] || return 0
  [ -s "$T/out/window.open" ] && kill -0 "$(cat "$T/out/window.watchdog" 2>/dev/null || echo none)" 2>/dev/null
}

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

# 6. open-vs-close race: W2's open waits for W1's close (same lock), then opens with its own token and live watchdog
open_window ok; echo 1 > "$T/s/delay"
close manual & P1=$!; sleep 0.2; opn W2 & P2=$!; wait $P1; r1=$?; wait $P2; r2=$?
ok "open/close race: close then open both succeed" '[ $r1 = 0 ] && [ $r2 = 0 ]'
ok "open/close race: W2 token and live W2 watchdog with global ON" 'grep -q "^W2-" $T/out/window.open && guarded && [ "$(cat $T/s/global)" = true ]'
ok "open/close race: two restarts (close, then open)" '[ "$(cat $T/s/restarts)" = 2 ]'
close manual; rc=$?; ok "open/close race: W2 closes cleanly afterwards" '[ $rc = 0 ] && [ ! -e $T/out/window.open ]'

# 7. an open while a window is active is rejected and touches nothing
open_window ok; opn W3; rc=$?
ok "open while active: rejected, no restart, token unchanged" '[ $rc = 2 ] && [ "$(cat $T/s/restarts)" = 0 ] && [ "$(cat $T/out/window.open)" = W-test-token ]'

# 8. a successful open releases the lock (its watchdog does not hold it): a close right after does not block
clean_stack ok; opn W4; rc=$?
ok "open: exit 0, global ON, guarded" '[ $rc = 0 ] && [ "$(cat $T/s/global)" = true ] && guarded'
close manual; rc=$?; ok "open: lock released, immediate close succeeds" '[ $rc = 0 ] && [ "$(cat $T/s/global)" = false ]'

# 9. injected restart failure while opening: the open releases the lock before closing (no deadlock), fails closed
clean_stack fail; opn W5; rc=$?
ok "failed open: exit 3 without deadlock" '[ $rc = 3 ]'
ok "failed open: close attempted and failed closed (backend stopped, token kept)" '[ "$(cat $T/s/backend)" = down ] && [ -e $T/out/window.open ]'
ok "failed open: never global ON without token and watchdog" 'guarded'

[ $FAILS -eq 0 ] && echo "all window-close tests passed" || { echo "$FAILS window-close test(s) failed"; exit 1; }
