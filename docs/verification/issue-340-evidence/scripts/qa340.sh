# shellcheck shell=bash
# ABOUTME: Helpers for manual QA suite #340 (Phase 1/2 regression recheck and consolidated release findings). Reuses the
# ABOUTME: #337 helpers (sessions, side-effect counters, fixtures); sets this suite's tag, phone range and output folder.
# shellcheck disable=SC2034  # sourced library: variables are used by the scripts that source it
#
# Source it from the other scripts. QA_HOME (default ~/qa333) holds the #333 stack and stub; OUTD (default ~/qa340/out)
# holds this suite's results and ids. Never prints tokens, cookies, CSRF values, passwords or secrets.
QA340_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
QA340_OUTD=${OUTD:-$HOME/qa340/out}
QA340_RESULTS=${RESULTS:-$QA340_OUTD/results.tsv}
export OUTD=$QA340_OUTD RESULTS=$QA340_RESULTS
mkdir -p "$OUTD"
. "$QA340_DIR/../../issue-337-evidence/scripts/qa337.sh"
# qa337.sh sets its own tag, phone range and OUTD: restore this suite's values.
export OUTD=$QA340_OUTD RESULTS=$QA340_RESULTS QA_TAG=QA340 QA_PHONE_PREFIX=+5698340
[ -f "$OUTD/ids" ] && . "$OUTD/ids"

# chk <case> <label> <expected> <actual> [note]: equality check recorded as PASS/FAIL.
chk() { rec "$1" "$2" "$([ "$3" = "$4" ] && echo PASS || echo FAIL)" "expected=$3 actual=$4${5:+ $5}"; }
# queue_pages <user> <tenant> [status]: walk every page of the follow-up queue (cursor pagination, page size 100) and
# print "<pages> <items> <duplicate ids>".
queue_pages() {
  local u=$1 t=$2 st=${3:-} cur="" pages=0 ids=$QA_TMP/qids.$$; : > "$ids"
  while :; do
    req "$u" "$t" GET "/api/follow-up-queue?limit=100${st:+&status=$st}${cur:+&cursor=$cur}" -
    [ "$CODE" = 200 ] || { echo "ERR http=$CODE"; return 1; }
    pages=$((pages + 1)); jb '.items[].customerId' >> "$ids"
    cur=$(jb '.nextCursor // empty'); [ -n "$cur" ] && [ $pages -lt 50 ] || break
  done
  echo "$pages $(wc -l < "$ids") $(sort "$ids" | uniq -d | wc -l)"; rm -f "$ids"
}
