package io.github.stevdrey.dokene.ai.eval;

/** Validation of the opt-in live evaluation settings. */
final class EvalLiveSettings {
    static final double DEFAULT_MIN_SUCCESS_RATIO = 0.5;

    private EvalLiveSettings() {
    }

    /**
     * Minimum share of provider-invoked cases that must deliver an outcome. Must be finite and in (0, 1]: zero or a
     * negative value would let an invalid key or a total provider outage finish green with nothing to grade.
     */
    static double minSuccessRatio(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MIN_SUCCESS_RATIO;
        }
        double ratio;
        try {
            ratio = Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("DOKENE_AI_EVAL_MIN_SUCCESS_RATIO must be a number in (0, 1]: " + raw, ex);
        }
        if (!Double.isFinite(ratio) || ratio <= 0 || ratio > 1) {
            throw new IllegalArgumentException("DOKENE_AI_EVAL_MIN_SUCCESS_RATIO must be finite and in (0, 1]: " + raw);
        }
        return ratio;
    }
}
