#!/usr/bin/env bash
# Issue 339 live journey wrapper around the WhatsApp QA stack; prints no secret values.
set -euo pipefail
wa=/home/stevdrey/.codex/.chatgpt-projects/g-p-6a8b7129b5c081919574ba9938baba0a/whatsapp-local
R=/home/stevdrey/Projects/Java/dokene-qa-run
export COMPOSE_PROJECT_NAME=dokene-qa-run
export COMPOSE_FILE="$R/compose.yaml:$wa/compose.whatsapp.yaml:/home/stevdrey/qa339/compose.qa339.yaml"
compose() { (cd "$R" && docker compose "$@"); }
case "${1:-status}" in
  status) compose ps ;;
  build) compose build migrate backend frontend ;;
  up) compose up -d --wait postgres keycloak oidc-bridge migrate backend frontend ;;
  outbound) DOKENE_WHATSAPP_TEST_OUTBOUND="${2:?true|false}" compose up -d --no-deps --force-recreate backend ;;
  tunnel) compose up -d webhook-gateway webhook-tunnel ;;
  url) c=dokene-qa-run-webhook-tunnel-1; docker logs --since "$(docker inspect "$c" --format '{{.State.StartedAt}}')" "$c" 2>&1 | sed -n 's/.*\(https:\/\/[a-z0-9-]*\.trycloudflare\.com\).*/\1/p' | tail -1 ;;
  gwlog) docker logs --since "${2:-30m}" dokene-qa-run-webhook-gateway-1 2>&1 ;;
  logs) compose logs --no-log-prefix --since "${2:-10m}" backend ;;
  stop-tunnel) compose stop webhook-tunnel webhook-gateway ;;
  down) compose stop ;;
  *) echo 'Usage: control.sh {status|build|up|outbound true|false|tunnel|url|gwlog|logs [since]|stop-tunnel|down}' >&2; exit 2 ;;
esac
