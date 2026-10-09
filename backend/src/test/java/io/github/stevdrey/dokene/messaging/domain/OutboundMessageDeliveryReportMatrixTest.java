// ABOUTME: Exhaustive proof of AC-3 for delivery reports: ten states by four statuses match the spec 0001 table.
// ABOUTME: Applied cells move state; every other cell yields Ignored with a non applied event and a version bump.
package io.github.stevdrey.dokene.messaging.domain;

import static io.github.stevdrey.dokene.messaging.domain.OutboundMessageTransitionMatrixTest.message;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OutboundMessageDeliveryReportMatrixTest {
    private static final Instant REPORTED = Instant.parse("2026-10-08T13:00:00Z");
    private static final Instant APPLIED_AT = Instant.parse("2026-10-08T13:00:05Z");

    /** (state/outcomeUnknown) -> (report status -> resulting state). */
    private static final Map<String, Map<DeliveryStatus, MessageStatus>> APPLIED = Map.of(
            "SENDING/true", Map.of(
                    DeliveryStatus.SENT, MessageStatus.SENT,
                    DeliveryStatus.DELIVERED, MessageStatus.DELIVERED,
                    DeliveryStatus.READ, MessageStatus.READ,
                    DeliveryStatus.FAILED, MessageStatus.FAILED),
            "SENT/false", Map.of(
                    DeliveryStatus.DELIVERED, MessageStatus.DELIVERED,
                    DeliveryStatus.READ, MessageStatus.READ,
                    DeliveryStatus.FAILED, MessageStatus.FAILED),
            "DELIVERED/false", Map.of(DeliveryStatus.READ, MessageStatus.READ));

    @Test
    void everyStateAndReportCellMatchesTheDeliveryReportTable() {
        int cells = 0;
        for (MessageStatus state : MessageStatus.values()) {
            for (boolean unknown : new boolean[] {false, true}) {
                if (unknown && state != MessageStatus.SENDING) {
                    continue;
                }
                Map<DeliveryStatus, MessageStatus> applied = APPLIED.getOrDefault(state + "/" + unknown, Map.of());
                for (DeliveryStatus reportStatus : DeliveryStatus.values()) {
                    cells++;
                    OutboundMessage message = message(state, unknown, 1);
                    var report = new DeliveryStatusReport("wamid.seed", reportStatus, REPORTED,
                            reportStatus == DeliveryStatus.FAILED ? Optional.of(FailureCategory.POLICY_VIOLATION)
                                    : Optional.empty());
                    DeliveryOutcome outcome = message.applyDeliveryReport(report, APPLIED_AT);
                    if (applied.containsKey(reportStatus)) {
                        assertThat(outcome).as("%s report on %s/%s", reportStatus, state, unknown)
                                .isInstanceOf(DeliveryOutcome.Applied.class);
                        Transition transition = ((DeliveryOutcome.Applied) outcome).transition();
                        assertThat(transition.next().status()).isEqualTo(applied.get(reportStatus));
                        assertThat(transition.next().outcomeUnknown()).isFalse();
                        assertThat(transition.next().version()).isEqualTo(message.version() + 1);
                        assertThat(transition.event().actorKind()).isEqualTo(MessageActorKind.PROVIDER);
                        assertThat(transition.event().type()).isEqualTo(MessageEventType.DELIVERY_UPDATED);
                        assertThat(transition.event().occurredAt()).isEqualTo(APPLIED_AT);
                        if (reportStatus == DeliveryStatus.FAILED) {
                            assertThat(transition.next().failureCategory()).isEqualTo(FailureCategory.POLICY_VIOLATION);
                        }
                    } else {
                        assertThat(outcome).as("%s report on %s/%s", reportStatus, state, unknown)
                                .isInstanceOf(DeliveryOutcome.Ignored.class);
                        var ignored = (DeliveryOutcome.Ignored) outcome;
                        assertThat(ignored.next().status()).isEqualTo(state);
                        assertThat(ignored.next().outcomeUnknown()).isEqualTo(unknown);
                        assertThat(ignored.next().version()).isEqualTo(message.version() + 1);
                        assertThat(ignored.event().applied()).isFalse();
                        assertThat(ignored.event().sequenceNumber()).isEqualTo(ignored.next().version());
                        assertThat(ignored.event().actorKind()).isEqualTo(MessageActorKind.PROVIDER);
                    }
                }
            }
        }
        assertThat(cells).isEqualTo(11 * DeliveryStatus.values().length);
    }

    @Test
    void lateDeliveredOrReadReportFromUnknownSendingSetsSentAtToTheReportTime() {
        OutboundMessage unknown = message(MessageStatus.SENDING, true, 1);
        var delivered = (DeliveryOutcome.Applied) unknown.applyDeliveryReport(
                new DeliveryStatusReport("wamid.seed", DeliveryStatus.DELIVERED, REPORTED, Optional.empty()), APPLIED_AT);
        assertThat(delivered.transition().next().sentAt()).isEqualTo(REPORTED);
        assertThat(delivered.transition().next().deliveredAt()).isEqualTo(REPORTED);

        var read = (DeliveryOutcome.Applied) delivered.transition().next().applyDeliveryReport(
                new DeliveryStatusReport("wamid.seed", DeliveryStatus.READ, REPORTED.plusSeconds(1), Optional.empty()),
                APPLIED_AT);
        assertThat(read.transition().next().readAt()).isEqualTo(REPORTED.plusSeconds(1));
        assertThat(read.transition().next().sentAt()).isEqualTo(REPORTED);
    }

    @Test
    void failedReportWithoutCategoryStoresUnknown() {
        var failed = (DeliveryOutcome.Applied) message(MessageStatus.SENT, false, 1).applyDeliveryReport(
                new DeliveryStatusReport("wamid.seed", DeliveryStatus.FAILED, REPORTED, Optional.empty()), APPLIED_AT);
        assertThat(failed.transition().next().failureCategory()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(failed.transition().event().failureCategory()).isEqualTo(FailureCategory.UNKNOWN);
    }
}
