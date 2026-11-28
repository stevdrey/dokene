// ABOUTME: What the store read about one window of admissions (ADR 0040): how many rows count, and the admitted_at
// ABOUTME: of the row whose leaving the window frees one unit, present only when the window is at or over its limit.
package io.github.stevdrey.dokene.admission;

import java.time.Instant;

/**
 * With {@code count} rows in the window and a limit {@code L}, {@code freesAt} is the row at position
 * {@code count - L + 1} from the oldest. That is the oldest row when the window is exactly full, and a later one
 * after a limit was lowered below the current count.
 */
record WindowUsage(int count, Instant freesAt) {
    WindowUsage {
        if (count < 0) {
            throw new IllegalArgumentException("Count must not be negative");
        }
    }
}
