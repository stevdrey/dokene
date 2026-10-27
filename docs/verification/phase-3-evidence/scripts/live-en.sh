#!/usr/bin/env bash
# Issue 140: one authorized English send. Opens the global window with a 1m interval, sends once, then restores
# tenant off, global off, interval 24h and the Spanish GENERAL_FOLLOW_UP mapping. Logs no identifiers.
cd /home/stevdrey/qa140
L=/home/stevdrey/qa140/out/live-140-en.txt; : > $L
log() { echo "$(date -u +%FT%TZ) $*" >> $L; }
wait_healthy() { for i in $(seq 1 60); do [ "$(docker inspect dokene-qa-run-backend-1 --format '{{.State.Health.Status}}')" = healthy ] && return 0; sleep 5; done; return 1; }
DOKENE_WHATSAPP_TEST_INTERVAL=1m ./control.sh outbound true >/dev/null 2>&1; wait_healthy
log "window: $(docker exec dokene-qa-run-backend-1 sh -c 'echo outbound=$DOKENE_MESSAGING_OUTBOUND_ENABLED interval=$DOKENE_MESSAGING_MIN_CONTACT_INTERVAL')"
timeout 120 python3 -I send140en.py enable >> $L 2>&1
timeout 180 python3 -I send140en.py send >> $L 2>&1; log "send-exit=$?"
timeout 120 python3 -I send140en.py disable >> $L 2>&1
./control.sh outbound false >/dev/null 2>&1; wait_healthy
log "closed: $(docker exec dokene-qa-run-backend-1 sh -c 'echo outbound=$DOKENE_MESSAGING_OUTBOUND_ENABLED interval=$DOKENE_MESSAGING_MIN_CONTACT_INTERVAL')"
timeout 120 python3 -I check140.py >> $L 2>&1
timeout 120 python3 -I send140en.py status >> $L 2>&1
log LIVEENDONE
