#!/usr/bin/env bash
# Issue 140 re-verification on main 80c7ab7: the regression #296 can affect, same drivers as regress.sh (base backend).
export PATH=$HOME/.nvm/versions/node/v24.21.0/bin:$PATH
O=/home/stevdrey/qa140/out/reverify; P=$O/progress.txt; : > $P
cd /home/stevdrey/Projects/Java/dokene-qa-run; unset COMPOSE_FILE
E=/home/stevdrey/Projects/Java/dokene/docs/verification/issue-119-evidence/scripts
count() { echo "$1 PASS=$(grep -c '^PASS' $2) FAIL=$(grep -c '^FAIL' $2) $(tail -1 $2)" >> $P; }
./scripts/dev-env.sh restart-backend --no-build --yes >/dev/null 2>&1; echo "base backend restarted: $?" >> $P
for s in v159 v162 recurrence; do ( timeout 1200 bash $E/$s.sh > $O/119-$s.txt 2>&1; echo "exit=$?" >> $O/119-$s.txt ); count 119-$s $O/119-$s.txt; done
for s in a b c d; do timeout 1500 bash /home/stevdrey/qa121/s/sec121-$s.sh > $O/121-$s.txt 2>&1; echo "exit=$?" >> $O/121-$s.txt; count 121-$s $O/121-$s.txt; done
echo ALLDONE >> $P
