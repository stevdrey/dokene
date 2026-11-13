FROM quay.io/keycloak/keycloak:26.8.2-2@sha256:c68e286cacd87b942ac1939a0a1481461109d6acd3a57dc496969a319a76bbf0 AS builder

ENV KC_HEALTH_ENABLED=true
ENV KC_DB=dev-file

WORKDIR /opt/keycloak
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.2-2@sha256:c68e286cacd87b942ac1939a0a1481461109d6acd3a57dc496969a319a76bbf0
COPY --from=builder --chown=keycloak:root /opt/keycloak/ /opt/keycloak/

USER 1000

WORKDIR /opt/keycloak
