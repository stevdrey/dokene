#!/usr/bin/env bash
# Issue 140: Phase 1/2 regression on main f4cb0ec (suites #119 API, #121 security, #122 UI), same drivers as the 2026-10-24 Path B run.
export PATH=$HOME/.nvm/versions/node/v24.21.0/bin:$PATH
O=/home/stevdrey/qa140/out; P=$O/progress.txt; : > $P
cd /home/stevdrey/Projects/Java/dokene-qa-run; unset COMPOSE_FILE
E=/home/stevdrey/Projects/Java/dokene/docs/verification/issue-119-evidence/scripts
count() { echo "$1 PASS=$(grep -c '^PASS' $2) FAIL=$(grep -c '^FAIL' $2) $(tail -1 $2)" >> $P; }
for s in reg-api v159 v162 recurrence; do ( cd /home/stevdrey/Projects/Java/dokene-qa-run; timeout 1200 bash $E/$s.sh > $O/119-$s.txt 2>&1; echo "exit=$?" >> $O/119-$s.txt ); count 119-$s $O/119-$s.txt; done
restart() { docker rm -f qa-stub >/dev/null 2>&1; ./scripts/dev-env.sh restart-backend --no-build --yes >/dev/null 2>&1; }
stub() { docker run -d --name qa-stub --network container:dokene-qa-run-oidc-bridge-1 -v /home/stevdrey/qa121/stub:/stub --entrypoint java dokene-backend:local -cp /stub Stub >/dev/null; sleep 3; }
run121() { timeout 1500 bash /home/stevdrey/qa121/s/sec121-$1.sh > $O/121-$1.txt 2>&1; echo "exit=$?" >> $O/121-$1.txt; count 121-$1 $O/121-$1.txt; }
for s in a b c d e f g; do run121 $s; done
export DOKENE_AI_OPENAI_BASE_URL=http://127.0.0.1:18080/v1; restart; stub
for s in h i j k l; do run121 $s; done
export COMPOSE_FILE=compose.yaml:/home/stevdrey/qa121/override.yaml; restart; stub; run121 m
unset COMPOSE_FILE; restart; stub
runjs() { ( cd /home/stevdrey/qa122; timeout 900 node $1.mjs > $O/ui-$1.txt 2>&1; echo "exit=$?" >> $O/ui-$1.txt ); echo "ui-$1 $(tail -1 $O/ui-$1.txt) fail-lines=$(grep -ciE '^FAIL' $O/ui-$1.txt)" >> $P; ps -eo pid,args | awk '/[h]eadless/{print $1}' | xargs -r kill 2>/dev/null; sleep 2; }
for s in ux06b ux07; do runjs $s; done
docker rm -f qa-stub >/dev/null 2>&1; export DOKENE_AI_OPENAI_BASE_URL=; restart
echo "backend back on the real OpenAI provider" >> $P
for s in ux01 ux01b ux02 ux03 ux04 ux05 ux06 ux08 ux09 ux10_11 ux11d; do runjs $s; done
echo ALLDONE >> $P
