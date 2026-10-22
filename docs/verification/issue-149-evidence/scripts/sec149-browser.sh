#!/usr/bin/env bash
# ABOUTME: Suite #149 wrapper for the browser cases: builds the P3-CSRF-03 target over the API, runs
# ABOUTME: sec149-browser.mjs with a headless Chromium, then checks the database half (no attempt row, still APPROVED).
#
# QA149_PW_DIR must hold node_modules/playwright-core (for example a scratch directory where
# `npm install playwright-core@1.52.0` ran); QA149_CHROME is the chrome-headless-shell binary.
cd "$(git rev-parse --show-toplevel)"; . docs/verification/issue-149-evidence/scripts/env149.sh
: "${QA149_PW_DIR:?set QA149_PW_DIR to the directory holding node_modules/playwright-core}"
export QA149_CHROME=${QA149_CHROME:-$HOME/Library/Caches/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-mac-arm64/chrome-headless-shell}
stub_reset; stub_set send accept
read -r c p MA <<< "$(ready_approved $A_OPERATOR "$A" "CSRF03 $RUN")"
n0=$(stub_calls send)
QA149_MA=$MA QA149_TENANT_A=$A node docs/verification/issue-149-evidence/scripts/sec149-browser.mjs; rc=$?
chk P3-CSRF-03 Database "no attempt row, message still APPROVED, no provider call" "0 APPROVED 0" "$(attempts_n "$MA") $(msg_status "$MA" | cut -d/ -f1) $(stub_since send $n0)"
[ $rc = 0 ] || rec P3-CSRF-03 Browser BLOCKED "sec149-browser.mjs exited $rc; see its output"
summary
