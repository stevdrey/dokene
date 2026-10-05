package io.github.stevdrey.dokene.ai.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Loads and structurally validates a dataset from the test classpath. */
public final class EvalDatasetLoader {
    public static final String DEFAULT_RESOURCE = "/ai-eval/v1/dataset.json";

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private EvalDatasetLoader() {
    }

    public static EvalDataset loadDefault() {
        return load(DEFAULT_RESOURCE);
    }

    public static EvalDataset load(String resource) {
        try (InputStream in = EvalDatasetLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Evaluation dataset not found: " + resource);
            }
            return validate(MAPPER.readValue(in, EvalDataset.class));
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read evaluation dataset " + resource, ex);
        }
    }

    static EvalDataset validate(EvalDataset dataset) {
        if (!dataset.syntheticOnly()) {
            throw new IllegalStateException("Dataset must be declared syntheticOnly");
        }
        if (dataset.datasetVersion() == null || dataset.datasetVersion().isBlank()) {
            throw new IllegalStateException("Dataset version is required");
        }
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (EvalCase c : dataset.cases()) {
            if (!ids.add(c.id())) {
                throw new IllegalStateException("Duplicate case id: " + c.id());
            }
            if (!names.add(c.displayName())) {
                throw new IllegalStateException("Duplicate case display name (used as scripted-provider key): " + c.displayName());
            }
        }
        Set<EvalCase.Family> covered = EnumSet.noneOf(EvalCase.Family.class);
        dataset.cases().forEach(c -> covered.add(c.family()));
        if (!covered.containsAll(EnumSet.allOf(EvalCase.Family.class))) {
            Set<EvalCase.Family> missing = EnumSet.allOf(EvalCase.Family.class);
            missing.removeAll(covered);
            throw new IllegalStateException("Dataset misses scenario families: " + missing);
        }
        return dataset;
    }
}
