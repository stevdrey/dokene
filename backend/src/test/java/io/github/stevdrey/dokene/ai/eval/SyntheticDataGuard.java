package io.github.stevdrey.dokene.ai.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Guard that keeps real-looking personal data and live links out of the evaluation dataset. It runs inside
 * {@link EvalDatasetLoader} so every consumer (deterministic CI and the opt-in live task) is protected before any
 * text can reach a provider. Violations name the kind only, never the offending text, which may be real PII.
 */
final class SyntheticDataGuard {
    static final String EMAIL_VIOLATION = "email address";
    static final String PHONE_VIOLATION = "phone number";
    static final String LINK_VIOLATION = "link outside reserved .test hosts";

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** Unicode-aware so NBSP, narrow NBSP, figure and thin spaces separate digit groups like a plain space. */
    private static final Pattern PHONE = Pattern.compile("(?U)(?<!\\d)\\+?(?:\\(?\\d\\)?[\\s.-]?){8,}(?!\\d)");

    private SyntheticDataGuard() {
    }

    /** Every String reachable from a record graph (records, lists, maps), so no text field is skipped. */
    static void collectTexts(Object value, List<String> out) {
        if (value == null) {
            return;
        }
        if (value instanceof String text) {
            out.add(text);
        } else if (value instanceof Collection<?> items) {
            items.forEach(item -> collectTexts(item, out));
        } else if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                collectTexts(k, out);
                collectTexts(v, out);
            });
        } else if (value.getClass().isRecord()) {
            for (var component : value.getClass().getRecordComponents()) {
                try {
                    collectTexts(component.getAccessor().invoke(value), out);
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }
    }

    /** Violation kinds found in the texts (no duplicates, no offending text). */
    static List<String> violations(List<String> texts) {
        List<String> found = new ArrayList<>();
        for (String text : texts) {
            if (EMAIL.matcher(text).find()) {
                add(found, EMAIL_VIOLATION);
            }
            if (PHONE.matcher(text).find()) {
                add(found, PHONE_VIOLATION);
            }
            var links = InvariantChecker.LINK.matcher(text);
            while (links.find()) {
                if (!hostOf(links.group()).endsWith(".test")) {
                    add(found, LINK_VIOLATION);
                }
            }
        }
        return found;
    }

    /** Throws naming the case and the violation kinds; the offending text is deliberately not echoed. */
    static void requireSynthetic(EvalDataset dataset) {
        List<String> problems = new ArrayList<>();
        for (EvalCase evalCase : dataset.cases()) {
            List<String> texts = new ArrayList<>();
            collectTexts(evalCase, texts);
            List<String> kinds = violations(texts);
            if (!kinds.isEmpty()) {
                problems.add(evalCase.id() + ": " + String.join(", ", kinds));
            }
        }
        List<String> datasetTexts = new ArrayList<>();
        collectTexts(dataset.description(), datasetTexts);
        List<String> kinds = violations(datasetTexts);
        if (!kinds.isEmpty()) {
            problems.add("dataset description: " + String.join(", ", kinds));
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Dataset is not synthetic (real-looking personal data or live links): " + String.join("; ", problems));
        }
    }

    /** Host of a detected link: scheme, credentials, port, path, query and fragment removed. */
    static String hostOf(String link) {
        String host = link.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "");
        host = host.replaceFirst("^[^/@]*@", "");
        return host.split("[/:?#\\s]", 2)[0].toLowerCase(Locale.ROOT).replaceAll("[.,;]+$", "");
    }

    private static void add(List<String> found, String kind) {
        if (!found.contains(kind)) {
            found.add(kind);
        }
    }
}
