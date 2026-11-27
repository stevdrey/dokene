// ABOUTME: The gauge dokene.messaging.provider.health.state (ADR 0038): 1 for the provider's current effective state
// ABOUTME: and 0 for the others, read through ProviderHealthReader at most once per memo period per instance.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessagingProviderKey;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A scrape reads every state's gauge, so the row is read once and remembered for {@link #MEMO}. When a read fails the
 * last state is reported for one more memo period, then no value (NaN) until a read works again: a stale 1 would
 * look like a live answer.
 */
public final class ProviderHealthStateGauge implements MeterBinder {
    static final String STATE = "dokene.messaging.provider.health.state";
    static final Duration MEMO = Duration.ofSeconds(10);

    private final ProviderHealthReader reader;
    private final MessagingProviderKey provider;
    private final LongSupplier nanoTime;
    private ProviderHealthState remembered;
    private boolean lastReadFailed;
    private boolean everRead;
    private long readAtNanos;

    public ProviderHealthStateGauge(ProviderHealthReader reader, MessagingProviderKey provider) {
        this(reader, provider, System::nanoTime);
    }

    ProviderHealthStateGauge(ProviderHealthReader reader, MessagingProviderKey provider, LongSupplier nanoTime) {
        this.reader = Objects.requireNonNull(reader, "Reader is required");
        this.provider = Objects.requireNonNull(provider, "Provider is required");
        this.nanoTime = Objects.requireNonNull(nanoTime, "Time source is required");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (ProviderHealthState state : ProviderHealthState.values()) {
            Gauge.builder(STATE, this, gauge -> gauge.value(state))
                    .tag("provider", provider.name()).tag("state", state.name()).register(registry);
        }
    }

    private synchronized double value(ProviderHealthState state) {
        long now = nanoTime.getAsLong();
        if (!everRead || now - readAtNanos >= MEMO.toNanos()) {
            try {
                remembered = reader.read(provider).state();
                lastReadFailed = false;
            } catch (RuntimeException unreadable) {
                // The reader's adapter already counted and logged it.
                if (lastReadFailed) {
                    remembered = null;
                }
                lastReadFailed = true;
            }
            everRead = true;
            readAtNanos = now;
        }
        if (remembered == null) {
            return Double.NaN;
        }
        return remembered == state ? 1 : 0;
    }
}
