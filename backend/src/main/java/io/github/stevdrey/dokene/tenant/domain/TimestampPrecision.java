package io.github.stevdrey.dokene.tenant.domain;

import java.time.DateTimeException;
import java.time.Instant;

final class TimestampPrecision {

    private static final long NANOSECONDS_PER_MICROSECOND = 1_000L;
    private static final long HALF_MICROSECOND_IN_NANOSECONDS = NANOSECONDS_PER_MICROSECOND / 2;

    private TimestampPrecision() {
    }

    static Instant normalize(Instant timestamp) {
        long roundedNanoseconds = ((timestamp.getNano() + HALF_MICROSECOND_IN_NANOSECONDS)
                / NANOSECONDS_PER_MICROSECOND) * NANOSECONDS_PER_MICROSECOND;
        try {
            return Instant.ofEpochSecond(timestamp.getEpochSecond(), roundedNanoseconds);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Timestamp cannot be represented with microsecond precision", exception);
        }
    }
}
