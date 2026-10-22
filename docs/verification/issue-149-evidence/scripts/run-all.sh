#!/usr/bin/env bash
# ABOUTME: Runs the whole suite #149 in order on one recorded commit and collects results.tsv plus every script output
# ABOUTME: under out/. Expects fixtures149.sh to have run once; restarts the backend several times (switches, limits).
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"
QA149_DIR=${QA149_DIR:-docs/verification/issue-149-evidence}; OUTD=${OUTD:-$QA149_DIR/out}; mkdir -p "$OUTD"
export OUTD
SHA=$(git rev-parse --short HEAD); echo "suite #149 on $SHA, $(date -u +%FT%TZ)" | tee "$OUTD/run.txt"
rm -f "$OUTD/results.tsv"
for s in perm idor rls csrf appr tmpl cons dup hook kill sec; do
  echo "== sec149-$s.sh" | tee -a "$OUTD/run.txt"
  bash "$QA149_DIR/scripts/sec149-$s.sh" > "$OUTD/sec149-$s.out" 2>&1
  tail -1 "$OUTD/sec149-$s.out" | tee -a "$OUTD/run.txt"
done
echo "== sec149-browser.sh" | tee -a "$OUTD/run.txt"
bash "$QA149_DIR/scripts/sec149-browser.sh" > "$OUTD/sec149-browser.out" 2>&1; tail -1 "$OUTD/sec149-browser.out" | tee -a "$OUTD/run.txt"
echo "== sec149-inprocess.sh" | tee -a "$OUTD/run.txt"
bash "$QA149_DIR/scripts/sec149-inprocess.sh" > "$OUTD/sec149-inprocess.out" 2>&1; tail -1 "$OUTD/sec149-inprocess.out" | tee -a "$OUTD/run.txt"
echo "== totals" | tee -a "$OUTD/run.txt"
awk -F'\t' '{n[$3]++} END {for (k in n) printf "%s=%d ", k, n[k]; print ""}' "$OUTD/results.tsv" | tee -a "$OUTD/run.txt"
echo "per case id (worst result wins):"
awk -F'\t' '{ if ($3=="FAIL") w[$1]="FAIL"; else if ($3=="BLOCKED" && w[$1]!="FAIL") w[$1]="BLOCKED"; else if (!($1 in w)) w[$1]="PASS" } END { for (k in w) print k"\t"w[k] }' "$OUTD/results.tsv" | sort > "$OUTD/verdicts.tsv"
awk -F'\t' '{n[$2]++} END {for (k in n) printf "%s=%d ", k, n[k]; print ""}' "$OUTD/verdicts.tsv" | tee -a "$OUTD/run.txt"
