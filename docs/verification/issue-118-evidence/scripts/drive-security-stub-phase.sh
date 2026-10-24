#!/usr/bin/env bash
# Driver: stub phase of suite #121 on main acb7856. h,i,j,k,l without the override; m with the override (1-min session, tenant AI limit 25).
cd /home/stevdrey/Projects/Java/dokene-qa-run
export DOKENE_AI_OPENAI_BASE_URL=http://127.0.0.1:18080/v1
restart() { docker rm -f qa-stub >/dev/null 2>&1; ./scripts/dev-env.sh restart-backend --no-build --yes >/dev/null 2>&1; docker run -d --name qa-stub --network container:dokene-qa-run-oidc-bridge-1 -v /home/stevdrey/qa121/stub:/stub --entrypoint java dokene-backend:local -cp /stub Stub >/dev/null; sleep 3; }
run() { bash /home/stevdrey/qa121/s/sec121-$1.sh > /home/stevdrey/qa-final/out/121-$1.txt 2>&1; echo "exit=$?" >> /home/stevdrey/qa-final/out/121-$1.txt; echo "sec121-$1 PASS=$(grep -c '^PASS' /home/stevdrey/qa-final/out/121-$1.txt) FAIL=$(grep -c '^FAIL' /home/stevdrey/qa-final/out/121-$1.txt)" >> /home/stevdrey/qa-final/out/progress.txt; }
: > /home/stevdrey/qa-final/out/progress.txt
unset COMPOSE_FILE; restart; run a; for s in h i j k l; do run $s; done
export COMPOSE_FILE=compose.yaml:/home/stevdrey/qa121/override.yaml; restart; run m
echo ALLDONE >> /home/stevdrey/qa-final/out/progress.txt
