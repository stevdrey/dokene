#!/usr/bin/env bash
# Smoke tests for scripts/dev-env.sh and the compose topology. Needs bash, python3 and the `docker compose` CLI
# (no running daemon or containers).
#
# Usage: ./scripts/tests/dev-env-smoke.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/dev-env.sh"
failures=0

check() {
  # check "description" command...
  local description="$1"; shift
  if "$@" >/dev/null 2>&1; then
    printf '[pass] %s\n' "$description"
  else
    printf '[FAIL] %s\n' "$description" >&2
    failures=$((failures + 1))
  fi
}

help_text="$("$SCRIPT" help 2>&1)"
help_lists_restart_backend() { grep -q 'restart-backend' <<<"$help_text"; }
help_has_no_code_leak() { ! grep -q 'set -uo pipefail' <<<"$help_text"; }
unknown_command_fails() { "$SCRIPT" definitely-not-a-command; [ $? -eq 2 ]; }
restart_backend_rejects_unknown_option() { "$SCRIPT" restart-backend --bogus; [ $? -ne 0 ]; }

check "help lists restart-backend" help_lists_restart_backend
check "help prints only the header comment" help_has_no_code_leak
check "unknown command exits 2" unknown_command_fails
check "restart-backend rejects unknown options" restart_backend_rejects_unknown_option
check "dev-env.sh and entrypoint.sh are valid bash" bash -c "bash -n '$SCRIPT' && bash -n '$ROOT_DIR/infra/docker/backend/entrypoint.sh'"

topology_is_inverted() {
  local env_vars="" v json=""
  for v in DOKENE_DB_NAME DOKENE_DB_BOOTSTRAP_USERNAME DOKENE_DB_BOOTSTRAP_PASSWORD DOKENE_DB_PASSWORD \
           DOKENE_DB_RUNTIME_PASSWORD DOKENE_DB_MIGRATION_PASSWORD DOKENE_TENANT_CONTEXT_SIGNING_KEY \
           KC_BOOTSTRAP_ADMIN_USERNAME KC_BOOTSTRAP_ADMIN_PASSWORD \
           SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET; do
    env_vars="$env_vars $v=unused"
  done
  json="$(cd "$ROOT_DIR" && env $env_vars docker compose config --format json)" || return 1
  python3 -I -c '
import json, sys
s = json.load(sys.stdin)["services"]
bridge, backend = s["oidc-bridge"], s["backend"]
assert backend["network_mode"] == "service:oidc-bridge", "backend must join the bridge namespace"
assert "network_mode" not in bridge, "bridge must own its namespace"
assert any(p.get("published") == "8080" for p in bridge.get("ports", [])), "bridge must publish 8080"
assert not backend.get("ports"), "backend must not publish ports itself"
assert "backend" in bridge["networks"]["default"]["aliases"], "bridge must carry the backend alias"
' <<<"$json"
}
check "compose: backend joins the oidc-bridge namespace (topology from #155)" topology_is_inverted

port_detection_is_privilege_free() {
  # Sourcing defines the functions without running a command.
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  local port pid
  port="$(python3 -I -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')" || return 1
  port_in_use "$port" && return 1   # free before the listener starts
  python3 -I -c 'import socket,sys,time; s=socket.socket(); s.bind(("127.0.0.1",int(sys.argv[1]))); s.listen(); time.sleep(30)' "$port" &
  pid=$!
  local i
  for i in 1 2 3 4 5 6 7 8 9 10; do port_in_use "$port" && break; sleep 0.2; done
  local used=1
  port_in_use "$port" && used=0
  kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
  [ "$used" -eq 0 ] && [ -n "$(port_listener "$port")" ]
}
check "port_in_use detects a listener without lsof/root (#163)" bash -c "$(declare -f port_detection_is_privilege_free) ; SCRIPT='$SCRIPT'; port_detection_is_privilege_free"

docker_mapping_counts_as_occupancy() {
  # userland-proxy=false: Docker publishes via NAT and no socket listens, but the mapping still blocks the port.
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  docker() { printf 'other-project-web\t127.0.0.1:%s->8080/tcp\n' "$FAKE_PORT"; }
  have() { [ "$1" = "docker" ]; }
  FAKE_PORT=45999
  port_in_use 45999 && ! port_in_use 45998 && [ "$(port_listener 45999)" = "Docker container 'other-project-web'" ]
}
check "port_in_use counts a Docker-published mapping without a listening socket" \
  bash -c "$(declare -f docker_mapping_counts_as_occupancy) ; SCRIPT='$SCRIPT'; docker_mapping_counts_as_occupancy"

published_mapping_parsing() {
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  hit()  { [ -n "$(printf 'web\t%s\n' "$2" | publishes_tcp_port "$1")" ]; }
  hit 8080 "127.0.0.1:8080->8080/tcp" \
    && hit 8080 "0.0.0.0:8080->8080/tcp, [::]:8080->8080/tcp" \
    && hit 8080 "127.0.0.1:8079-8081->8079-8081/tcp" \
    && hit 8080 "127.0.0.1:8080->9999/udp, 127.0.0.1:8080->8080/tcp" \
    && ! hit 8080 "127.0.0.1:8080->9999/udp" \
    && ! hit 8082 "127.0.0.1:8079-8081->8079-8081/tcp" \
    && ! hit 8080 "127.0.0.1:18080->8080/tcp" \
    && ! hit 8080 "8080/tcp"
}
check "published mappings: TCP only, ranges honoured" \
  bash -c "$(declare -f published_mapping_parsing) ; SCRIPT='$SCRIPT'; published_mapping_parsing"

if [ "$failures" -gt 0 ]; then
  printf '%s check(s) failed\n' "$failures" >&2
  exit 1
fi
printf 'All checks passed\n'
