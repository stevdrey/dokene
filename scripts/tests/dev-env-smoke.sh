#!/usr/bin/env bash
# Smoke tests for scripts/dev-env.sh and the compose topology. Needs bash, python3 and the `docker compose` CLI
# (no running daemon or containers).
#
# Usage: ./scripts/tests/dev-env-smoke.sh
set -uo pipefail
# Test stubs (docker, have, warn, compose) shadow functions the sourced dev-env.sh calls; ShellCheck cannot see that.
# shellcheck disable=SC2329

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
restart_backend_rejects_unknown_option() { ! "$SCRIPT" restart-backend --bogus; }

check "help lists restart-backend" help_lists_restart_backend
check "help prints only the header comment" help_has_no_code_leak
check "unknown command exits 2" unknown_command_fails
check "restart-backend rejects unknown options" restart_backend_rejects_unknown_option
# Scripts that are ShellCheck-clean today. The scripts/verify-issue-*.sh helpers still carry findings and are not listed.
shellcheck_targets=(
  scripts/dev-env.sh scripts/tests/dev-env-smoke.sh scripts/tests/nginx-headers-smoke.sh scripts/seed-local-qa.sh scripts/verify-keycloak-bff-e2e.sh
  scripts/keycloak-apply-session-revocation.sh scripts/tests/keycloak-apply-session-revocation-test.sh
  infra/docker/backend/entrypoint.sh infra/docker/postgres/init/01-create-database-roles.sh
)
shellcheck_min_version=0.11.0   # the annotations target this release (SC2329 does not exist before it)
shellcheck_version="$(shellcheck --version 2>/dev/null | sed -n 's/^version: //p')"
if [ -n "$shellcheck_version" ] \
   && [ "$(printf '%s\n%s\n' "$shellcheck_min_version" "$shellcheck_version" | sort -V | head -n 1)" = "$shellcheck_min_version" ]; then
  if shellcheck_output="$(cd "$ROOT_DIR" && shellcheck -x "${shellcheck_targets[@]}" 2>&1)"; then
    printf '[pass] %s\n' "shell scripts are ShellCheck-clean"
  else
    printf '[FAIL] %s\n%s\n' "shell scripts are ShellCheck-clean" "$shellcheck_output" >&2
    failures=$((failures + 1))
  fi
else
  printf '[skip] ShellCheck >= %s not installed\n' "$shellcheck_min_version"
fi
check "dev-env.sh and entrypoint.sh are valid bash" bash -c "bash -n '$SCRIPT' && bash -n '$ROOT_DIR/infra/docker/backend/entrypoint.sh'"

# Render the Compose model with throwaway values for the required variables (no daemon or containers needed).
compose_config_json() {
  local -a env_vars=()
  local v
  for v in DOKENE_DB_NAME DOKENE_DB_BOOTSTRAP_USERNAME DOKENE_DB_BOOTSTRAP_PASSWORD DOKENE_DB_PASSWORD \
           DOKENE_DB_RUNTIME_PASSWORD DOKENE_DB_MIGRATION_PASSWORD DOKENE_TENANT_CONTEXT_SIGNING_KEY \
           KC_BOOTSTRAP_ADMIN_USERNAME KC_BOOTSTRAP_ADMIN_PASSWORD \
           SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET; do
    env_vars+=("$v=unused")
  done
  (cd "$ROOT_DIR" && env "${env_vars[@]}" docker compose config --format json)
}

topology_is_inverted() {
  local json=""
  json="$(compose_config_json)" || return 1
  python3 -I -c '
import json, re, sys
s = json.load(sys.stdin)["services"]
bridge, backend = s["oidc-bridge"], s["backend"]
assert backend["network_mode"] == "service:oidc-bridge", "backend must join the bridge namespace"
assert "network_mode" not in bridge, "bridge must own its namespace"
assert any(p.get("published") == "8080" for p in bridge.get("ports", [])), "bridge must publish 8080"
assert not backend.get("ports"), "backend must not publish ports itself"
assert "backend" in bridge["networks"]["default"]["aliases"], "bridge must carry the backend alias"
assert re.search(r"nslookup\s+oidc-bridge\.(\s|$)", " ".join(bridge["healthcheck"]["test"])), \
    "bridge must health-check name resolution with an absolute name (#171)"
assert backend["depends_on"]["oidc-bridge"]["condition"] == "service_healthy", "backend must wait for a healthy bridge"
' <<<"$json"
}
check "compose: backend joins the oidc-bridge namespace (topology from #155)" topology_is_inverted

migration_credentials_stay_in_migrate() {
  local json=""
  json="$(compose_config_json)" || return 1
  python3 -I -c '
import json, sys
s = json.load(sys.stdin)["services"]
migrate, backend = s["migrate"], s["backend"]
names = lambda svc: [x["source"] for x in svc.get("secrets", [])]
assert not [k for k in backend.get("environment", {}) if "MIGRATION" in k or k.endswith("SIGNING_KEY")], \
    "backend environment must hold no migration credential or signing key"
assert names(backend) == ["DOKENE_TENANT_CONTEXT_SIGNING_KEY"], "backend gets only the signing key secret"
assert sorted(names(migrate)) == ["DOKENE_DB_MIGRATION_PASSWORD", "DOKENE_TENANT_CONTEXT_SIGNING_KEY"]
assert not [k for k in migrate.get("environment", {}) if k.endswith(("PASSWORD", "SIGNING_KEY"))], \
    "migrate reads its secrets from files, not the environment"
assert backend["depends_on"]["migrate"]["condition"] == "service_completed_successfully"
assert migrate["read_only"] and migrate["restart"] == "no"
' <<<"$json"
}
check "compose: only the migrate job holds the migration credential (#308)" migration_credentials_stay_in_migrate

compose_minimum_includes_patch() {
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  compose_version_supported 2 27 2 && compose_version_supported 2 28 0 && compose_version_supported 5 6 1 \
    && ! compose_version_supported 2 27 1 && ! compose_version_supported 2 17 0 && ! compose_version_supported 1 99 9
}
check "compose minimum is 2.27.2, patch included (#308 review)" \
  bash -c "$(declare -f compose_minimum_includes_patch) ; SCRIPT='$SCRIPT'; compose_minimum_includes_patch"

doctor_does_not_write_secret_files() {
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  # shellcheck disable=SC2329  # stubs the sourced cmd_doctor must not reach
  resolve_secret_files() { echo called; }
  # shellcheck disable=SC2329
  write_secret_files() { echo called; }
  check_docker() { :; }; check_tools() { :; }; check_env() { :; }; check_ports() { :; }; ok() { :; }
  [ -z "$(cmd_doctor)" ]
}
check "doctor leaves secret files to the start commands (#308 review)" \
  bash -c "$(declare -f doctor_does_not_write_secret_files) ; SCRIPT='$SCRIPT'; doctor_does_not_write_secret_files"

# Runs the real `docker compose config --environment` against a copy of compose.yaml and a quoted .env (#308 review).
secret_files_follow_compose_interpolation() {
  local work key rc=1
  work="$(mktemp -d)" || return 1
  # Built at run time: a synthetic 64-hex value, kept out of the source so secret scanners have nothing to match.
  key="$(printf '0123456789abcdef%.0s' 1 2 3 4)"
  cp "$ROOT_DIR/compose.yaml" "$work/"
  cat > "$work/.env" <<ENV
DOKENE_DB_NAME=dokene
DOKENE_DB_BOOTSTRAP_USERNAME=admin
DOKENE_DB_BOOTSTRAP_PASSWORD=unused
DOKENE_DB_PASSWORD=unused
DOKENE_DB_RUNTIME_PASSWORD=unused
DOKENE_DB_MIGRATION_PASSWORD="quoted pass"
DOKENE_TENANT_CONTEXT_SIGNING_KEY='$key'
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET=unused
DOKENE_SECRETS_DIR=./custom-secrets
ENV
  (
    # shellcheck source=../dev-env.sh
    source "$SCRIPT" || exit 1
    ROOT_DIR="$work"
    CALLS=""; RM_FAILS=false
    warn() { :; }; fail() { :; }
    compose() {
      if [ "$1" = "rm" ]; then CALLS="$CALLS|$*"; [ "$RM_FAILS" = "false" ]; else (cd "$ROOT_DIR" && docker compose "$@"); fi
    }
    refresh_secret_files || exit 1
    [ "$(cat "$work/custom-secrets/DOKENE_DB_MIGRATION_PASSWORD")" = "quoted pass" ] || exit 1
    [ "$(cat "$work/custom-secrets/DOKENE_TENANT_CONTEXT_SIGNING_KEY")" = "$key" ] || exit 1
    [ ! -e "$work/.secrets" ] || exit 1
    [ "$(stat -c %a "$work/custom-secrets" 2>/dev/null || stat -f %Lp "$work/custom-secrets")" = "700" ] || exit 1
    # Unchanged values remove nothing.
    refresh_secret_files && [ -z "$CALLS" ] || exit 1
    # A changed key whose container removal fails keeps the old file, so the next run detects the change again.
    sed -i.bak "s/^DOKENE_TENANT_CONTEXT_SIGNING_KEY=.*/DOKENE_TENANT_CONTEXT_SIGNING_KEY=$(printf 'fedcba9876543210%.0s' 1 2 3 4)/" "$work/.env"
    RM_FAILS=true
    ! refresh_secret_files || exit 1
    [ "$(cat "$work/custom-secrets/DOKENE_TENANT_CONTEXT_SIGNING_KEY")" = "$key" ] || exit 1
    RM_FAILS=false; CALLS=""
    refresh_secret_files && [ "$CALLS" = "|rm -f -s migrate backend" ] || exit 1
    [ "$(cat "$work/custom-secrets/DOKENE_TENANT_CONTEXT_SIGNING_KEY")" != "$key" ] || exit 1
    # A shared, world-writable directory is refused and left as it was.
    mkdir -p "$work/shared" && chmod 1777 "$work/shared"
    sed -i.bak "s|^DOKENE_SECRETS_DIR=.*|DOKENE_SECRETS_DIR=$work/shared|" "$work/.env"
    ! refresh_secret_files || exit 1
    [ "$(stat -c %a "$work/shared" 2>/dev/null || stat -f %Lp "$work/shared")" = "1777" ] || exit 1
    [ ! -e "$work/shared/DOKENE_DB_MIGRATION_PASSWORD" ]
  ) && rc=0
  rm -rf "$work"
  return "$rc"
}
check "secret files: Compose-resolved values, safe DOKENE_SECRETS_DIR, recreation survives a failed removal (#308)" secret_files_follow_compose_interpolation

# Runs the real healthcheck command in a container whose resolver has a search domain. Needs a Docker daemon.
healthcheck_survives_search_domain() {
  local json image probe net="dokene-smoke-$$" ctr="dokene-smoke-$$-bridge" rc=1
  json="$(compose_config_json)" || return 1
  image="$(python3 -I -c 'import json,sys; print(json.load(sys.stdin)["services"]["oidc-bridge"]["image"])' <<<"$json")" || return 1
  probe="$(python3 -I -c 'import json,sys; print(json.load(sys.stdin)["services"]["oidc-bridge"]["healthcheck"]["test"][1])' <<<"$json")" || return 1
  docker network create "$net" >/dev/null || return 1
  # The container is named like the service so that the probe resolves it through the network's embedded DNS.
  if docker run -d --rm --name "$ctr" --network "$net" --network-alias oidc-bridge --dns-search lan \
       --entrypoint sleep "$image" 60 >/dev/null; then
    # The relative form must fail here (proves the setup reproduces #171); the shipped probe must pass.
    if ! docker exec "$ctr" sh -c 'nslookup oidc-bridge >/dev/null 2>&1' \
       && docker exec "$ctr" sh -c "$probe"; then
      rc=0
    fi
  fi
  docker rm -f "$ctr" >/dev/null 2>&1
  docker network rm "$net" >/dev/null 2>&1
  return $rc
}
if docker info >/dev/null 2>&1; then
  check "oidc-bridge healthcheck passes with a DNS search domain (#171)" healthcheck_survives_search_domain
else
  printf '[skip] oidc-bridge healthcheck passes with a DNS search domain (#171): no Docker daemon\n'
fi

port_detection_is_privilege_free() {
  # Sourcing defines the functions without running a command.
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  local port pid
  port="$(python3 -I -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')" || return 1
  port_in_use "$port" && return 1   # free before the listener starts
  python3 -I -c 'import socket,sys,time; s=socket.socket(); s.bind(("127.0.0.1",int(sys.argv[1]))); s.listen(); time.sleep(30)' "$port" &
  pid=$!
  for _ in 1 2 3 4 5 6 7 8 9 10; do port_in_use "$port" && break; sleep 0.2; done
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

networkless_detection() {
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  [ "$(printf 'b\tcreated\t0\n' | networkless_containers)" = "b" ] \
    && [ "$(printf 'b\trunning\t0\n' | networkless_containers)" = "b" ] \
    && [ -z "$(printf 'b\trunning\t1\n' | networkless_containers)" ] \
    && [ -z "$(printf 'b\texited\t0\n' | networkless_containers)" ]
}
check "a bridge attached to no network is detected (#164)" \
  bash -c "$(declare -f networkless_detection) ; SCRIPT='$SCRIPT'; networkless_detection"

repair_removes_only_broken_bridge() {
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  CALLS=""
  warn() { :; }
  compose() { if [ "$1" = "ps" ]; then echo "${FAKE_ID:-}"; else CALLS="$CALLS|$*"; fi; }
  docker() { printf '/dokene-oidc-bridge-1\t%s\n' "$FAKE_STATE"; }
  FAKE_ID=abc FAKE_STATE="created	0"; repair_networkless_bridge
  [ "$CALLS" = "|rm -f -s oidc-bridge backend" ] || return 1
  CALLS=""; FAKE_STATE="running	1"; repair_networkless_bridge; [ -z "$CALLS" ] || return 1
  CALLS=""; FAKE_STATE="exited	0"; repair_networkless_bridge; [ -z "$CALLS" ] || return 1
  CALLS=""; FAKE_ID=""; FAKE_STATE="created	0"; repair_networkless_bridge; [ -z "$CALLS" ]
}
check "repair removes a network-less bridge and leaves healthy or stopped ones (#164)" \
  bash -c "$(declare -f repair_removes_only_broken_bridge) ; SCRIPT='$SCRIPT'; repair_removes_only_broken_bridge"

discard_removes_created_services() {
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  CALLS=""
  warn() { :; }
  compose() { if [ "$1" = "ps" ]; then printf '%s' "$FAKE_STALE"; else CALLS="$CALLS|$*"; fi; }
  FAKE_STALE=$'backend\noidc-bridge'; discard_unstarted_containers
  [ "$CALLS" = "|rm -f -s backend oidc-bridge" ] || return 1
  CALLS=""; FAKE_STALE=""; discard_unstarted_containers; [ -z "$CALLS" ]
}
check "failed start removes created services only" \
  bash -c "$(declare -f discard_removes_created_services) ; SCRIPT='$SCRIPT'; discard_removes_created_services"

start_failure_detection() {
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  local f; f="$(mktemp)"
  echo 'Error response from daemon: driver failed programming external connectivity: Bind for 127.0.0.1:8080 failed: port is already allocated' > "$f"
  is_start_failure_log "$f" || { rm -f "$f"; return 1; }
  echo 'Error response from daemon: pull access denied for dokene-backend, repository does not exist' > "$f"
  ! is_start_failure_log "$f"; local rc=$?
  rm -f "$f"; return $rc
}
check "only start/bind errors count as a start failure" \
  bash -c "$(declare -f start_failure_detection) ; SCRIPT='$SCRIPT'; start_failure_detection"

if [ "$failures" -gt 0 ]; then
  printf '%s check(s) failed\n' "$failures" >&2
  exit 1
fi
printf 'All checks passed\n'
