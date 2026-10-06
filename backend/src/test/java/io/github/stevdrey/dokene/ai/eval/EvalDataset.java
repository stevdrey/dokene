package io.github.stevdrey.dokene.ai.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Versioned synthetic dataset. {@code syntheticOnly} must be true; the loader refuses anything else. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record EvalDataset(String datasetVersion, boolean syntheticOnly, String description, List<EvalCase> cases) {
    public EvalDataset {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
