# 26.8.2-3 ships Red Hat OpenJDK 21.0.13.1, the vendor fix for Java4Shell (CVE-2026-114938, issue 429); 26.8.2-2
# shipped the affected 21.0.13.
FROM quay.io/keycloak/keycloak:26.8.2-3@sha256:7e66a96e4739239070aeaf72e4c5decf0f7f682cfc725b9f7ea2b336eaba38b3 AS builder

ENV KC_HEALTH_ENABLED=true
ENV KC_DB=dev-file

WORKDIR /opt/keycloak
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.2-3@sha256:7e66a96e4739239070aeaf72e4c5decf0f7f682cfc725b9f7ea2b336eaba38b3
COPY --from=builder --chown=keycloak:root /opt/keycloak/ /opt/keycloak/

USER 1000

WORKDIR /opt/keycloak
