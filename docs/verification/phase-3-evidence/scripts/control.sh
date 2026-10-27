#!/usr/bin/env bash
# Issue 140 live journey wrapper around the WhatsApp QA stack; prints no secret values.
set -euo pipefail
wa=/home/stevdrey/.codex/.chatgpt-projects/g-p-6a8b7129b5c081919574ba9938baba0a/whatsapp-local
compose() {
  docker compose --project-name dokene-qa-run \
    --project-directory /home/stevdrey/Projects/Java/dokene-qa-run \
    -f /home/stevdrey/Projects/Java/dokene-qa-run/compose.yaml \
    -f "$wa/compose.whatsapp.yaml" -f /home/stevdrey/qa140/compose.qa140.yaml "$@"
}
case "${1:-status}" in
  status) compose ps ;;
  up) compose up -d postgres keycloak oidc-bridge backend frontend ;;
  outbound) DOKENE_WHATSAPP_TEST_OUTBOUND="${2:?true|false}" compose up -d --no-deps --force-recreate backend ;;
  tunnel) compose up -d webhook-gateway webhook-tunnel ;;
  url) c=dokene-qa-run-webhook-tunnel-1; docker logs --since "$(docker inspect "$c" --format '{{.State.StartedAt}}')" "$c" 2>&1 | sed -n 's/.*\(https:\/\/[a-z0-9-]*\.trycloudflare\.com\).*/\1/p' | tail -1 ;;
  logs) compose logs --no-log-prefix --since "${2:-10m}" backend ;;
  stop-tunnel) compose stop webhook-tunnel webhook-gateway ;;
  *) echo 'Usage: control.sh {status|up|outbound true|false|tunnel|url|logs [since]|stop-tunnel}' >&2; exit 2 ;;
esac
