FROM quay.io/keycloak/keycloak:26.7.3 AS builder

ENV KC_HEALTH_ENABLED=true
ENV KC_DB=dev-file

WORKDIR /opt/keycloak
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.7.3
COPY --from=builder --chown=keycloak:root /opt/keycloak/ /opt/keycloak/

USER 1000

WORKDIR /opt/keycloak
