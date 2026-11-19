#!/usr/bin/env bash
# ABOUTME: Offline test for w2-restart335.sh with the real window-close334.sh against a fake stack (no Docker, no HTTP):
# ABOUTME: a closed, replaced or unwatched W2 window is never re-enabled, including a close racing the crash restart.
# shellcheck disable=SC2034  # rc, r1 and r2 are read inside the strings that ok() evaluates
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
T=$(mktemp -d); trap 'pkill -P $$ 2>/dev/null; rm -rf "$T"' EXIT
FAILS=0
ok() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; FAILS=$((FAILS + 1)); fi; }
cp "$HERE/w2-restart335.sh" "$HERE/../../issue-334-evidence/scripts/window-close334.sh" "$T/"
# Fake stack: files under $T/s (global, tenant, backend, restart mode, delay, restart count and the last ON request).
cat > "$T/qa334.sh" <<EOF
S=$T/s; OUTD=$T/out; A=tenant-a; QA_FAILS=0; mkdir -p "\$OUTD"
STACK333=$T/stack.sh
rec() { printf '%s\t%s\t%s\t-\t%s\n' "\$1" "\$2" "\$3" "\$4" >> "\$OUTD/results.tsv"; }
assert_safe_stack() { [ "\$(cat \$S/backend)" = up ] && [ "\$(cat \$S/global)" = "\$1" ]; }
qa_login() { [ "\$(cat \$S/backend)" = up ]; }
integ() { :; }; etag() { echo '"1"'; }
req() { if [ "\$(cat \$S/backend)" = up ]; then CODE=200; case "\$5" in *true*) echo true;; *) echo false;; esac > \$S/tenant; else CODE=000; fi; }
tenant_state() { echo "true/true/\$(cat \$S/tenant)"; }
stub_sends() { echo 0; }
stop_backend() { echo down > \$S/backend; }
backend_running() { [ "\$(cat \$S/backend)" = up ]; }
EOF
printf '. "%s/qa334.sh"\n' "$T" > "$T/qa335.sh"
cat > "$T/stack.sh" <<EOF
#!/usr/bin/env bash
S=$T/s; echo \$((\$(cat \$S/restarts) + 1)) > \$S/restarts; sleep "\$(cat \$S/delay)"
[ "\$(cat \$S/mode)" = fail ] && exit 1
echo up > \$S/backend
if [ "\$QA333_OUTBOUND" = true ]; then echo true > \$S/global; echo "\$QA335_MIN_INTERVAL/\$QA335_CLAIM_TIMEOUT" > \$S/on-env; else echo false > \$S/global; fi
EOF
# crashed_window: W2 open (global ON, token, live watchdog), then the backend killed by the crash probe.
crashed_window() {
  rm -rf "$T/s" "$T/out"; mkdir -p "$T/s" "$T/out"
  echo true > "$T/s/global"; echo true > "$T/s/tenant"; echo down > "$T/s/backend"
  echo 0 > "$T/s/restarts"; echo ok > "$T/s/mode"; echo 0 > "$T/s/delay"; : > "$T/out/results.tsv"
  echo "W2-test-token" > "$T/out/window.open"
  setsid bash -c 'sleep 300' </dev/null >/dev/null 2>&1 & WD=$!; echo "$WD" > "$T/out/window.watchdog"
}
restart() { timeout 20 bash "$T/w2-restart335.sh" "$@" >/dev/null 2>&1; }
close() { timeout 20 bash "$T/window-close334.sh" "$@" >/dev/null 2>&1; }
# guarded: the safety invariant. A global-ON stack always has a window token and a live watchdog.
guarded() {
  [ "$(cat "$T/s/backend")" = up ] && [ "$(cat "$T/s/global")" = true ] || return 0
  [ -s "$T/out/window.open" ] && kill -0 "$(cat "$T/out/window.watchdog" 2>/dev/null || echo none)" 2>/dev/null
}

# 1. window still active: restart with global ON and the W2 overrides
crashed_window; restart W2-test-token; rc=$?
ok "active window: exit 0, one restart, global ON" '[ $rc = 0 ] && [ "$(cat $T/s/restarts)" = 1 ] && [ "$(cat $T/s/global)" = true ]'
ok "active window: W2 overrides passed to the restart" '[ "$(cat $T/s/on-env)" = 3m/2m ]'
ok "active window: guarded" 'guarded'
close manual

# 2. window closed after the crash, before the restart: abort, global stays OFF, no orphaned ON window
crashed_window; close manual; restart W2-test-token; rc=$?
ok "closed first: restart aborts with exit 6" '[ $rc = 6 ]'
ok "closed first: only the close restarted (global OFF)" '[ "$(cat $T/s/restarts)" = 1 ] && [ "$(cat $T/s/global)" = false ]'
ok "closed first: guarded" 'guarded'

# 3. race: the close holds the lock (slow restart) when the crash restart arrives; the restart waits, then aborts
crashed_window; echo 1 > "$T/s/delay"
close manual & P1=$!; sleep 0.2; restart W2-test-token & P2=$!; wait $P1; r1=$?; wait $P2; r2=$?
ok "close vs crash restart: close exit 0, restart exit 6" '[ $r1 = 0 ] && [ $r2 = 6 ]'
ok "close vs crash restart: global OFF, a single restart, guarded" '[ "$(cat $T/s/global)" = false ] && [ "$(cat $T/s/restarts)" = 1 ] && guarded'

# 4. the watchdog closes the window (TTL) before the crash restart: same outcome
crashed_window; close watchdog; restart W2-test-token; rc=$?
ok "watchdog closed first: exit 6, global OFF, guarded" '[ $rc = 6 ] && [ "$(cat $T/s/global)" = false ] && guarded'

# 5. a later window replaced W2 (other token): abort, nothing restarted
crashed_window; echo "W3-other" > "$T/out/window.open"; restart W2-test-token; rc=$?
ok "replaced window: exit 6, no restart" '[ $rc = 6 ] && [ "$(cat $T/s/restarts)" = 0 ]'

# 6. token present but the watchdog is dead: abort, backend stays down (cannot send)
crashed_window; kill "$WD"; wait "$WD" 2>/dev/null; restart W2-test-token; rc=$?
ok "dead watchdog: exit 6, no restart, backend still down" '[ $rc = 6 ] && [ "$(cat $T/s/restarts)" = 0 ] && [ "$(cat $T/s/backend)" = down ]'

# 7. negative control: without the guard (plain ON restart after a close) the invariant breaks, so the test can fail
crashed_window; close manual; QA333_OUTBOUND=true QA335_MIN_INTERVAL=3m QA335_CLAIM_TIMEOUT=2m bash "$T/stack.sh"
ok "negative control: an unguarded restart after close leaves global ON with no window" '! guarded'

[ $FAILS -eq 0 ] && echo "all w2-restart tests passed" || { echo "$FAILS w2-restart test(s) failed"; exit 1; }
