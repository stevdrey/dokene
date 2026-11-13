FROM quay.io/keycloak/keycloak:26.7.3@sha256:29be7252db0a106f1cd2ac17b9a56ff2668073da645638a38b9fc67deeb2d6c4 AS builder

ENV KC_HEALTH_ENABLED=true
ENV KC_DB=dev-file

WORKDIR /opt/keycloak
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.7.3@sha256:29be7252db0a106f1cd2ac17b9a56ff2668073da645638a38b9fc67deeb2d6c4
COPY --from=builder --chown=keycloak:root /opt/keycloak/ /opt/keycloak/

USER 1000

WORKDIR /opt/keycloak
