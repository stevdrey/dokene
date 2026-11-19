#!/usr/bin/env bash
# ABOUTME: Suite #340 environment record: candidate SHA, product-tree identity with the running images, migrations,
# ABOUTME: provider modes, switch state, stub and denial baselines and the tenant A/B data volume. Read-only.
set -uo pipefail
. "$(dirname "$0")/qa340.sh"
C=ENV
qa_login testuser || exit 1
head=$(git -C "$WT" rev-parse HEAD)
rec $C "candidate worktree SHA" NOTE "sha=$head"
img=$(docker inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' dokene-qa333-backend-1 2>/dev/null)
rec $C "product tree of the candidate equals the tree the running images were built from (91915e2f)" \
  "$( { [ -z "$(git -C "$WT" diff --stat 91915e2f "$head" -- . ':!docs')" ]; } && echo PASS || echo FAIL)" "image_label=${img:-none}"
rec $C "flyway: latest successful migration" NOTE "$(psqlq "select max(version::int)||' all_ok='||bool_and(success) from dokene.flyway_schema_history where version is not null")"
rec $C "AI provider / outbound env" NOTE "$(docker compose -f "$WT/compose.yaml" exec -T backend sh -c 'echo provider=$DOKENE_AI_PROVIDER outbound=$DOKENE_MESSAGING_OUTBOUND_ENABLED')"
req testuser "$A" GET /api/integrations/whatsapp -
rec $C "global outbound off (env) and tenant A integration outbound off" \
  "$( { [ "$(jb .outboundEnabled)" = false ] && [ "$(docker compose -f "$WT/compose.yaml" exec -T backend sh -c 'echo $DOKENE_MESSAGING_OUTBOUND_ENABLED')" = false ]; } && echo PASS || echo FAIL)" \
  "http=$CODE tenant_outbound=$(jb .outboundEnabled) integration_enabled=$(jb .enabled)"
setid STUB0_340 "$(stub_sends)"; setid DENY0_340 "$(deny_n)"
rec $C "baselines: stub sends and denial rows" NOTE "stub_sends=$STUB0_340 denials=$DENY0_340"
for T in "$A" "$B"; do
  rec $C "tenant ${T:0:8} volume" NOTE "$(psqlq "set app.tenant_id='$T'; select 'customers='||count(*) filter (where status='ACTIVE')||' archived='||count(*) filter (where status='ARCHIVED') from dokene.customers where tenant_id='$T'" | tail -1) queue=$(queue_pages testuser "$T")"
done
qa_finish
