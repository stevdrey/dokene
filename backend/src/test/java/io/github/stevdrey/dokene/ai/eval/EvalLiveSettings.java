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

    /**
     * Optional price table (USD per 1M tokens). Both rates absent means no cost estimate; both must otherwise be
     * finite and non-negative. A single rate or a nonsensical value is a configuration mistake that would silently
     * disable or corrupt cost comparisons, so it fails fast.
     */
    static EvalReportBuilder.Pricing pricing(String rawInput, String rawOutput) {
        boolean noInput = rawInput == null || rawInput.isBlank();
        boolean noOutput = rawOutput == null || rawOutput.isBlank();
        if (noInput && noOutput) {
            return null;
        }
        if (noInput || noOutput) {
            throw new IllegalArgumentException("DOKENE_AI_EVAL_PRICE_INPUT_PER_MTOK and "
                    + "DOKENE_AI_EVAL_PRICE_OUTPUT_PER_MTOK must be supplied together");
        }
        return new EvalReportBuilder.Pricing(rate("DOKENE_AI_EVAL_PRICE_INPUT_PER_MTOK", rawInput),
                rate("DOKENE_AI_EVAL_PRICE_OUTPUT_PER_MTOK", rawOutput));
    }

    private static double rate(String name, String raw) {
        double rate;
        try {
            rate = Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(name + " must be a number: " + raw, ex);
        }
        if (!Double.isFinite(rate) || rate < 0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative: " + raw);
        }
        return rate;
    }
}
