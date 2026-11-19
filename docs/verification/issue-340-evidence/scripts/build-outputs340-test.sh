#!/usr/bin/env bash
# ABOUTME: Negative controls for build-outputs340.py: a complete passing input set builds the bundle; a missing,
# ABOUTME: incomplete, errored or failed browser run, a wrong REG-02 copy result or a FAIL result line builds nothing.
# Self-contained: synthetic inputs in a temporary directory, no stack needed. Usage: bash build-outputs340-test.sh
set -uo pipefail
BUILDER="$(cd "$(dirname "$0")" && pwd)/build-outputs340.py"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
FAILURES=0
ok() { printf 'ok   %s\n' "$1"; }
bad() { printf 'FAIL %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

good() { # good <dir>: a complete, passing input set
  local d=$1 br i; mkdir -p "$d"
  printf 'REG-01\tcheck\tPASS\t2026-11-19T04:00:00Z\tnote\n' > "$d/results.tsv"
  printf 'REG-01\tBrowser\tobservation\tPASS\n' > "$d/browser-notes.tsv"
  printf 'generated_length\t190\ngenerated_is_spanish\ttrue\ngenerated_no_link_or_price\ttrue\nclipboard_equals_edited_draft\ttrue\nedited_suffix_in_clipboard\ttrue\ncopy_feedback\tBorrador copiado\nlocal_edit_notice\ttrue\nno_send_or_approve_without_review\ttrue\n' > "$d/reg02-copy.txt"
  for br in chromium firefox; do
    for i in $(seq 1 12); do printf 'ASSERT\tREG-04\t%s\tcheck %d\tPASS\tdetail\n' "$br" "$i"; done > "$d/reg04-ui-$br.txt"
    printf 'DONE\t%s\tasserts=12\tfailed=0\n' "$br" >> "$d/reg04-ui-$br.txt"
  done
  printf 'Container keycloak Healthy\n' > "$d/kc-clean.log"
}
# case <label> <want: built|refused> <mutation command run inside the input dir>
case_() {
  local label=$1 want=$2 mut=$3 d="$T/$RANDOM$RANDOM" got
  good "$d"; ( cd "$d" && eval "$mut" )
  rm -f "$d/out.txt"
  if python3 "$BUILDER" "$d" "$d/out.txt" > /dev/null 2>&1 && [ -s "$d/out.txt" ]; then got=built; else got=refused; fi
  [ -e "$d/out.txt" ] && [ "$want" = refused ] && got="refused but wrote a bundle"
  [ "$got" = "$want" ] && ok "$label: $want" || bad "$label: expected $want, got $got"
}
case_ "complete passing inputs" built ":"
case_ "missing Firefox browser log" refused "rm reg04-ui-firefox.txt"
case_ "empty Chromium browser log" refused ": > reg04-ui-chromium.txt"
case_ "incomplete browser run (no DONE line)" refused "sed -i '/^DONE/d' reg04-ui-chromium.txt"
case_ "browser run stopped early (too few assertions)" refused "sed -i '5,12d' reg04-ui-firefox.txt"
case_ "failed browser assertion" refused "sed -i '3s/\tPASS\t/\tFAIL\t/' reg04-ui-chromium.txt"
case_ "browser exception" refused "printf 'ERROR\tchromium\texception\tTimeout\n' >> reg04-ui-chromium.txt"
case_ "DONE line reporting failures" refused "sed -i 's/failed=0/failed=1/' reg04-ui-firefox.txt"
case_ "clipboard did not match the edited draft" refused "sed -i 's/^clipboard_equals_edited_draft\ttrue/clipboard_equals_edited_draft\tfalse/' reg02-copy.txt"
case_ "copy feedback missing" refused "sed -i '/^copy_feedback/d' reg02-copy.txt"
case_ "REG-02 exception" refused "printf 'ERROR\tTimeout\n' >> reg02-copy.txt"
case_ "missing REG-02 log" refused "rm reg02-copy.txt"
case_ "FAIL result line" refused "printf 'REG-03\tcheck\tFAIL\t2026-11-19T04:10:00Z\tnote\n' >> results.tsv"
case_ "FAIL browser observation" refused "printf 'REG-02\tBrowser\tobservation\tFAIL\n' >> browser-notes.tsv"
case_ "missing Keycloak log" refused "rm kc-clean.log"
if [ "$FAILURES" -gt 0 ]; then printf '%d check(s) failed\n' "$FAILURES"; exit 1; fi
printf 'all build-outputs340 checks passed\n'
