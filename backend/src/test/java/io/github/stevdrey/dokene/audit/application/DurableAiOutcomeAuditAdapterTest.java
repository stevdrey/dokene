package io.github.stevdrey.dokene.audit.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.audit.domain.AiAuditDetail;
import io.github.stevdrey.dokene.audit.domain.AiAuditOperation;
import io.github.stevdrey.dokene.audit.domain.AiAuditOutcome;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.ActionGateRejectionReason;
import io.github.stevdrey.dokene.followup.application.AiUnavailableReason;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class DurableAiOutcomeAuditAdapterTest {
    private final AuditRecorder recorder = mock(AuditRecorder.class);
    private final DurableAiOutcomeAuditAdapter adapter = new DurableAiOutcomeAuditAdapter(recorder);
    private final CustomerId customerId = new CustomerId(UUID.randomUUID());

    @Test
    void mapsGeneratedAndModelRefusedWithoutDetail() {
        adapter.generated(customerId, AiOperation.NEXT_BEST_ACTION);
        adapter.modelRefused(customerId, AiOperation.MESSAGE_DRAFT);

        verify(recorder).aiInvocationOutcome(customerId.value(), AiAuditOperation.NEXT_BEST_ACTION,
                AiAuditOutcome.GENERATED, AiAuditDetail.NONE);
        verify(recorder).aiInvocationOutcome(customerId.value(), AiAuditOperation.MESSAGE_DRAFT,
                AiAuditOutcome.MODEL_REFUSED, AiAuditDetail.NONE);
        verifyNoMoreInteractions(recorder);
    }

    @ParameterizedTest
    @EnumSource(ActionGateRejectionReason.class)
    void mapsEveryGateRejectionReasonToItsOwnAuditDetail(ActionGateRejectionReason reason) {
        adapter.gateRejected(customerId, AiOperation.NEXT_BEST_ACTION, reason);

        verify(recorder).aiInvocationOutcome(customerId.value(), AiAuditOperation.NEXT_BEST_ACTION,
                AiAuditOutcome.GATE_REJECTED, AiAuditDetail.valueOf(reason.name()));
    }

    @ParameterizedTest
    @EnumSource(value = AiUnavailableReason.class,
            names = {"DISALLOWED_ACTION", "DISALLOWED_TEMPLATE_INTENT", "INVALID_RECOMMENDATION"},
            mode = EnumSource.Mode.EXCLUDE)
    void mapsEveryFailureReasonToItsOwnAuditDetail(AiUnavailableReason reason) {
        adapter.failed(customerId, AiOperation.MESSAGE_DRAFT, reason);

        verify(recorder).aiInvocationOutcome(customerId.value(), AiAuditOperation.MESSAGE_DRAFT,
                AiAuditOutcome.FAILED, AiAuditDetail.valueOf(reason.name()));
    }

    @Test
    void auditVocabularyCoversEveryClosedSourceVocabulary() {
        assertThat(Arrays.stream(io.github.stevdrey.dokene.ai.application.AiFailureCategory.values()).map(Enum::name))
                .allSatisfy(name -> AiAuditDetail.valueOf(name));
        assertThat(Arrays.stream(ActionGateRejectionReason.values()).map(Enum::name))
                .allSatisfy(name -> AiAuditDetail.valueOf(name));
        assertThat(Arrays.stream(AiOperation.values()).map(Enum::name))
                .allSatisfy(name -> AiAuditOperation.valueOf(name));
    }
}
