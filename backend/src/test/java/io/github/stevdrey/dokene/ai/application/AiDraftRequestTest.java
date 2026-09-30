package io.github.stevdrey.dokene.ai.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AiDraftRequestTest {

    private final DraftContext context = mock(DraftContext.class);

    @Test
    void rejectsNullZeroAndNegativeTimeouts() {
        assertThatThrownBy(() -> new AiDraftRequest(context, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AiDraftRequest(context, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiDraftRequest(context, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
