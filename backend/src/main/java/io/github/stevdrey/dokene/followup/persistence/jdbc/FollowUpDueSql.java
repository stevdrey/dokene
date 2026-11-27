// ABOUTME: The single SQL derivation of due follow-ups (ADR 0013): shared by the operator due queue and the recurring
// ABOUTME: candidate generation (ADR 0036) so both apply the same archive, do-not-contact, consent and cadence rules.
package io.github.stevdrey.dokene.followup.persistence.jdbc;

/**
 * {@link #EVALUATED_CANDIDATES} is a {@code WITH} clause ending in the {@code evaluated_candidates} CTE. It binds three
 * parameters in order: the tenant id, the evaluation instant (as a {@code timestamptz}) and the tenant id again. The
 * tenant-local date {@code tenant_today} comes from the tenant's IANA zone in the same statement, so the zone, the
 * cadence and the timing inputs are one snapshot.
 */
final class FollowUpDueSql {
    static final String EVALUATED_CANDIDATES = """
                WITH tenant_policy AS (
                    SELECT cadence_days, time_zone
                    FROM dokene.tenant_follow_up_policies
                    WHERE tenant_id = ?
                ),
                raw_candidates AS (
                    SELECT
                        c.id AS customer_id,
                        c.display_name,
                        (SELECT p.normalized_phone FROM dokene.customer_phone_contacts p
                         JOIN dokene.customer_contact_consents cc
                           ON cc.tenant_id = p.tenant_id AND cc.customer_id = p.customer_id AND cc.phone_contact_id = p.id
                         WHERE p.tenant_id = c.tenant_id AND p.customer_id = c.id
                           AND cc.channel = 'WHATSAPP' AND cc.status = 'GRANTED'
                         ORDER BY p.is_primary DESC, p.id ASC LIMIT 1) AS primary_phone,
                        cfp.version AS policy_version,
                        COALESCE(cfp.cadence_days, tp.cadence_days) AS effective_cadence,
                        cfp.snoozed_until,
                        cfp.explicit_next_date,
                        cfp.last_manual_follow_up_date,
                        cfp.last_dismissed_date,
                        cfp.last_outbound_message_date,
                        GREATEST(cfp.last_manual_follow_up_date, cfp.last_dismissed_date,
                                 cfp.last_outbound_message_date) AS last_action_date,
                        lp.last_purchase_at,
                        (lp.last_purchase_at AT TIME ZONE tp.time_zone)::date AS last_purchase_date,
                        (CAST(? AS timestamptz) AT TIME ZONE tp.time_zone)::date AS tenant_today
                    FROM dokene.customers c
                    JOIN tenant_policy tp ON true
                    JOIN dokene.customer_follow_up_policies cfp ON cfp.tenant_id = c.tenant_id AND cfp.customer_id = c.id
                    LEFT JOIN LATERAL (
                        SELECT pur.purchased_at AS last_purchase_at
                        FROM dokene.purchases pur
                        WHERE pur.tenant_id = c.tenant_id AND pur.customer_id = c.id AND pur.status = 'VALID'
                        ORDER BY pur.purchased_at DESC, pur.id DESC
                        LIMIT 1
                    ) lp ON true
                    WHERE c.tenant_id = ?
                      AND c.status = 'ACTIVE'
                      AND NOT EXISTS (
                          SELECT 1 FROM dokene.customer_do_not_contact dnc
                          WHERE dnc.tenant_id = c.tenant_id AND dnc.customer_id = c.id AND dnc.enabled = true
                      )
                      AND EXISTS (
                          SELECT 1 FROM dokene.customer_phone_contacts phone
                          JOIN dokene.customer_contact_consents cc
                            ON cc.tenant_id = phone.tenant_id AND cc.customer_id = phone.customer_id AND cc.phone_contact_id = phone.id
                          WHERE phone.tenant_id = c.tenant_id AND phone.customer_id = c.id
                            AND cc.channel = 'WHATSAPP' AND cc.status = 'GRANTED'
                      )
                ),
                evaluated_candidates AS (
                    SELECT
                        customer_id,
                        display_name,
                        primary_phone,
                        policy_version,
                        effective_cadence,
                        last_purchase_at,
                        last_manual_follow_up_date,
                        last_dismissed_date,
                        tenant_today,
                        CASE
                            WHEN snoozed_until IS NOT NULL AND snoozed_until >= tenant_today THEN snoozed_until
                            WHEN explicit_next_date IS NOT NULL THEN explicit_next_date
                            WHEN last_action_date IS NOT NULL
                                 AND (last_purchase_date IS NULL OR last_action_date >= last_purchase_date)
                                THEN last_action_date + effective_cadence
                            WHEN last_purchase_date IS NOT NULL THEN last_purchase_date + effective_cadence
                            ELSE NULL
                        END AS due_date,
                        CASE
                            WHEN snoozed_until IS NOT NULL AND snoozed_until >= tenant_today THEN 'SNOOZE'
                            WHEN explicit_next_date IS NOT NULL THEN 'EXPLICIT_DATE'
                            WHEN last_action_date IS NOT NULL
                                 AND (last_purchase_date IS NULL OR last_action_date >= last_purchase_date) THEN
                                CASE
                                    WHEN last_manual_follow_up_date = last_action_date THEN 'LAST_MANUAL_FOLLOW_UP'
                                    WHEN last_dismissed_date = last_action_date THEN 'LAST_DISMISSAL'
                                    ELSE 'LAST_OUTBOUND_MESSAGE'
                                END
                            WHEN last_purchase_date IS NOT NULL THEN 'LAST_PURCHASE'
                            ELSE 'NONE'
                        END AS timing_source
                    FROM raw_candidates
                )
            """;

    /**
     * Customers that are due or overdue today and not inside an active snooze. Padded with spaces because a text block
     * drops the trailing space after the {@code WHERE} it follows.
     */
    static final String DUE_PREDICATE =
            " due_date IS NOT NULL AND due_date <= tenant_today AND timing_source <> 'SNOOZE' ";

    private FollowUpDueSql() {
    }
}
