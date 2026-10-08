#!/usr/bin/env bash
# Waits for the OIDC discovery document and then starts the backend.
#
# Spring Security fetches the discovery document at startup from the configured issuer
# (http://localhost:<KEYCLOAK_PORT>/realms/dokene). Inside Docker that address is served by the
# `oidc-bridge` sidecar that shares this container's network namespace, which may start a moment later.
set -Eeuo pipefail

issuer="${SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_DOKENE_ISSUER_URI:-}"
if [[ -n "$issuer" ]]; then
  authority="${issuer#*://}"
  authority="${authority%%/*}"
  host="${authority%%:*}"
  port="${authority##*:}"
  [[ "$port" == "$authority" ]] && port=80
  path="${issuer#*://}"
  path="/${path#*/}/.well-known/openid-configuration"
  timeout="${DOKENE_OIDC_WAIT_SECONDS:-180}"
  deadline=$((SECONDS + timeout))
  echo "Waiting for OIDC discovery at ${host}:${port}${path} (up to ${timeout}s)"
  until (
    exec 3<>"/dev/tcp/${host}/${port}" \
      && printf 'GET %s HTTP/1.0\r\nHost: %s:%s\r\n\r\n' "$path" "$host" "$port" >&3 \
      && head -n 1 <&3 | grep -q ' 200 '
  ) 2>/dev/null; do
    if (( SECONDS >= deadline )); then
      echo "Error: OIDC discovery document is not reachable at ${issuer} after ${timeout}s." >&2
      echo "Check that the keycloak and oidc-bridge containers are running (docker compose ps)." >&2
      echo "If only the backend was recreated, oidc-bridge may be attached to its old network namespace:" >&2
      echo "run ./scripts/dev-env.sh restart-backend (or: docker compose up -d backend oidc-bridge)." >&2
      exit 1
    fi
    sleep 2
  done
fi

exec java -jar /app/dokene.jar
