#!/usr/bin/env bash
# ABOUTME: P3-MQA-SEC-07: the database backstop posture behind the #149 P3-RLS cases on this stack, and the comparison
# ABOUTME: of this suite's observations with #149's accepted constraints and #305's open findings (recorded as NOTE).
set -uo pipefail
. "$(dirname "$0")/qa337.sh"
C=SEC-07
T="outbound_messages outbound_message_events outbound_send_attempts outbound_message_idempotency_keys customers customer_phone_contacts tenant_messaging_integrations tenant_provider_templates audit_events"
bad=""; for t in $T; do
  r=$(psqlq "select relrowsecurity::text||relforcerowsecurity::text from pg_class c join pg_namespace n on n.oid=c.relnamespace where nspname='dokene' and relname='$t'")
  [ "$r" = truetrue ] || bad="$bad $t:$r"
done
rec $C "forced row-level security on the tenant tables these probes touched (#149 P3-RLS posture)" "$( { [ -z "$bad" ]; } && echo PASS || echo FAIL)" "tables=$(wc -w <<<"$T") not_forced=${bad:-none}"
rec $C "runtime role is not superuser and has no BYPASSRLS" "$( { [ "$(psqlq "select rolsuper::text||rolbypassrls::text from pg_roles where rolname='dokene_runtime'")" = falsefalse ]; } && echo PASS || echo FAIL)" "-"
rec $C "#265 (any-of denial writes two rows): fixed on this candidate, one row per denied cancel (SEC-01, SEC-06)" NOTE "regression check, not a new finding"
rec $C "#266 (cookie on /webhooks siblings): fixed on this candidate, no sibling sets a cookie (SEC-06)" NOTE "regression check, not a new finding"
rec $C "#307 M3 (IdP logout ends BFF sessions): confirmed by the back-channel logout probe (SEC-03)" NOTE "closed finding, behaviour holds"
rec $C "#310 L1 (denial audit amplification, open): every denied request still writes one AUTHORIZATION_DENIED row with no bound" NOTE "known open hardening; denial rows on the stack: $(deny_n), ${DENY_BEFORE:-68} before this suite; not a fresh regression"
rec $C "ADR 0024 #210/#142: tenant B may store an unverified claim of A's phone number id or A's reference name; proof, routing and sends stay contained (SEC-02)" NOTE "by design (no oracle); not a finding"
rec $C "#312 L3 (self-provisioning) and #318 L7 (redirect origin): outside this suite's routes, not probed" NOTE "open, unchanged; tracked in #305"
# Recorded FAIL lines make the exit status nonzero.
qa_finish
