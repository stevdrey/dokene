#!/usr/bin/env bash
# ABOUTME: Suite #149, in-process rows (P3-PERM-13, P3-HOOK-12, P3-SEC-09, P3-SEC-11, P3-KILL-07) and every automated
# ABOUTME: fixture the case matrix names: runs those backend test classes once and reads build/test-results for each.
#
# Needs Docker (Testcontainers) and the JDK the build declares. Run from the repository root; the results go to
# $OUTD/results.tsv like the HTTP suites, channel "In process" or "Automated".
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"
QA149_DIR=${QA149_DIR:-docs/verification/issue-149-evidence}; export OUTD=${OUTD:-$QA149_DIR/out}; mkdir -p "$OUTD"
MATRIX=docs/verification/phase-3-security-cases.md
RESULTS=$OUTD/results.tsv; PASSN=0; FAILN=0; BLOCKN=0
rec() { printf '%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$(date -u +%FT%TZ)" "$4" >> "$RESULTS"
  case $3 in PASS) PASSN=$((PASSN+1));; FAIL) FAILN=$((FAILN+1));; BLOCKED) BLOCKN=$((BLOCKN+1));; esac; echo "$3  $1  [$2]  $4"; }

# Rows whose action is an internal call. The matrix names the test for the first three; the last two have no route on
# the local stack (no actuator, no in-process switch) and are covered by the tests named here.
INPROCESS="P3-PERM-13|AuditIntegrationTest.protectsReadsAndAppendOnlyPermissions
P3-HOOK-12|OutboundMessagingIntegrationTest.deliverySinkRequiresTheProviderContextAndRefusesAMemberContext
P3-HOOK-12|DefaultDeliveryStatusSinkTest.refusesToRunWithoutAProviderContext
P3-SEC-09|LocalIntegrationSecretResolverTest
P3-SEC-09|GraphApiMessagingProviderTest.noSecretPhoneOrMessageTextFromASecretBearingErrorReachesResultsOrLogs
P3-SEC-11|WebhookTelemetryTest
P3-KILL-07|WhatsAppOutboundSafetyControlTest"

# Every `ClassTest.method` or bare `ClassTest` the matrix names in a P3 row, keyed by the row id.
AUTOMATED=$(grep -E '^\| P3-' "$MATRIX" | while IFS= read -r line; do
  id=$(echo "$line" | cut -d'|' -f2 | tr -d ' ')
  echo "$line" | grep -o -E '`[A-Za-z]+Test(\.[A-Za-z]+)*`' | tr -d '`' | sed "s/^/$id|/"
done | sort -u)

ALL=$(printf '%s\n%s\n' "$INPROCESS" "$AUTOMATED")
CLASSES=$(echo "$ALL" | cut -d'|' -f2 | cut -d. -f1 | sort -u)
echo "running $(echo "$CLASSES" | wc -l | tr -d ' ') test classes in backend/"
FILTERS=(); for c in $CLASSES; do FILTERS+=(--tests "*.$c"); done
( cd backend && ./gradlew test "${FILTERS[@]}" --console=plain --quiet ) > "$OUTD/sec149-inprocess.gradle.log" 2>&1
echo "gradle exit $? (log: $OUTD/sec149-inprocess.gradle.log)"

# result <Class[.Nested...][.method]> -> "passed n" | "failed n" | "absent"
result() {
  local spec=$1 cls=${1%%.*} rest=${1#*.} files m n f
  [ "$rest" = "$1" ] && rest=""
  files=$(ls backend/build/test-results/test/TEST-*."$cls".xml backend/build/test-results/test/TEST-*."$cls"\$*.xml 2>/dev/null)
  [ -z "$files" ] && { echo absent; return; }
  if [ -z "$rest" ]; then
    n=$(cat $files | grep -c '<testcase '); f=$(cat $files | grep -c -E '<(failure|error)')
  else
    # The last segment is the method unless it names a nested class; nested segments live in the file name.
    m=${rest##*.}; local nested=${rest%.*}; [ "$nested" = "$rest" ] && nested=""
    local sel; sel=$(for x in $files; do case $nested in "") echo "$x";; *) echo "$x" | grep -q "\$${nested##*.}.xml" && echo "$x";; esac; done)
    if [ -z "$sel" ]; then echo absent; return; fi
    if cat $sel | grep -q "<testcase name=\"$m[(\"]"; then
      n=$(cat $sel | grep -c "<testcase name=\"$m[(\"]")
      f=$(cat $sel | awk -v m="$m" '/<testcase name="/{open=index($0,"name=\""m"(")==0 && index($0,"name=\""m"\"")==0 ? 0 : 1} open && /<(failure|error)/{c++} END{print c+0}')
    else
      # A bare nested class name (for example GlobalSwitchOff): count the whole file.
      local nestedsel; nestedsel=$(for x in $files; do echo "$x" | grep -q "\$$m.xml" && echo "$x"; done)
      if [ -n "$nestedsel" ]; then sel=$nestedsel
      # A parameterized method reports its cases under display names; fall back to the class when the source declares it.
      elif grep -rq "void $m(" backend/src/test/java --include="$cls.java"; then :
      else echo absent; return; fi
      n=$(cat $sel | grep -c '<testcase '); f=$(cat $sel | grep -c -E '<(failure|error)')
      [ -z "$nestedsel" ] && [ "$f" = 0 ] && { echo "passed $n in the class, method parameterized"; return; }
    fi
  fi
  [ "$n" = 0 ] && { echo absent; return; }
  [ "$f" = 0 ] && echo "passed $n" || echo "failed $f/$n"
}

echo "== in-process rows"
echo "$INPROCESS" | while IFS='|' read -r id spec; do
  r=$(result "$spec"); case $r in passed*) rec "$id" "In process" PASS "$spec ($r)";; absent) rec "$id" "In process" BLOCKED "$spec is not on this commit";; *) rec "$id" "In process" FAIL "$spec ($r)";; esac
done
echo "== automated fixtures named in the matrix"
echo "$AUTOMATED" | while IFS='|' read -r id spec; do
  r=$(result "$spec"); case $r in passed*) rec "$id" Automated PASS "$spec ($r)";; absent) rec "$id" Automated BLOCKED "$spec is not on this commit";; *) rec "$id" Automated FAIL "$spec ($r)";; esac
done
echo "== sec149-inprocess.sh: see $RESULTS (gradle log in $OUTD/sec149-inprocess.gradle.log)"
