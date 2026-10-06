package io.github.stevdrey.dokene.ai.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Writes the machine-readable (JSON) and human-readable (Markdown) forms of a report. */
public final class EvalReportWriter {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private EvalReportWriter() {
    }

    public static String toJson(EvalReport report) {
        return MAPPER.writeValueAsString(report) + "\n";
    }

    public static EvalReport fromJson(String json) {
        return MAPPER.readValue(json, EvalReport.class);
    }

    public static EvalReport read(Path file) throws IOException {
        return fromJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Writes {@code <base>.json} and {@code <base>.md} under {@code dir}; returns the JSON path. */
    public static Path write(EvalReport report, Path dir, String base) throws IOException {
        Files.createDirectories(dir);
        Path json = dir.resolve(base + ".json");
        Files.writeString(json, toJson(report), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(base + ".md"), toMarkdown(report), StandardCharsets.UTF_8);
        return json;
    }

    public static String toMarkdown(EvalReport report) {
        StringBuilder md = new StringBuilder();
        EvalReport.Summary summary = report.summary();
        md.append("# AI evaluation report\n\n");
        md.append("| Field | Value |\n| --- | --- |\n");
        row(md, "Mode", report.mode());
        row(md, "Dataset version", report.datasetVersion());
        row(md, "Provider", report.provider());
        row(md, "Model", report.model());
        row(md, "Contract fingerprint", report.contractFingerprint());
        row(md, "Prompt/context policy label", report.promptPolicyLabel());
        row(md, "Generated at", report.generatedAt());
        row(md, "Cases", String.valueOf(summary.totalCases()));
        md.append("\n## Hard invariants (delivered layer, pass/fail)\n\n");
        md.append("Result: **").append(summary.allDeliveredInvariantsPass() ? "ALL PASS" : "FAILURES PRESENT").append("**\n\n");
        md.append("| Invariant | Applicable | Passed | Failed |\n| --- | ---: | ---: | ---: |\n");
        summary.deliveredInvariants().forEach((id, tally) -> md.append("| ").append(id).append(" | ")
                .append(tally.applicable()).append(" | ").append(tally.passed()).append(" | ")
                .append(tally.failed()).append(" |\n"));
        if (summary.behaviorMatches() != null) {
            md.append("\nPinned platform behavior: ").append(summary.behaviorMatches()).append(" match, ")
                    .append(summary.behaviorMismatches()).append(" mismatch.\n");
        }
        EvalReport.RawTotals raw = summary.rawModelFindings();
        md.append("\n## Raw model findings (before the gate; informational, never traded against invariants)\n\n");
        md.append("| Finding | Cases |\n| --- | ---: |\n");
        md.append("| Schema-invalid structured output | ").append(raw.schemaInvalid()).append(" |\n");
        md.append("| Provider failure | ").append(raw.providerFailure()).append(" |\n");
        md.append("| Allowlist/intent violation | ").append(raw.allowlistViolation()).append(" |\n");
        md.append("| Unsafe draft content | ").append(raw.unsafeDraft()).append(" |\n");
        md.append("| Refusal / no recommendation | ").append(raw.refusal()).append(" |\n");
        EvalReport.Usage usage = summary.usage();
        md.append("\n## Usage and latency (no aggregate score)\n\n| Metric | Value |\n| --- | ---: |\n");
        row(md, "Provider calls", String.valueOf(usage.providerCalls()));
        row(md, "Reported latency p50/p95 (ms)", usage.reportedLatencyP50Ms() + " / " + usage.reportedLatencyP95Ms());
        row(md, "Wall latency p50/p95 per operation, retries included (ms)", usage.wallLatencyP50Ms() == null ? "n/a (deterministic)"
                : usage.wallLatencyP50Ms() + " / " + usage.wallLatencyP95Ms());
        row(md, "Input / output tokens", usage.inputTokens() + " / " + usage.outputTokens());
        row(md, "Price table (USD per 1M tokens, input / output)", usage.inputUsdPerMillionTokens() == null ? "n/a"
                : usage.inputUsdPerMillionTokens() + " / " + usage.outputUsdPerMillionTokens());
        row(md, "Estimated cost (USD)", usage.estimatedCostUsd() == null
                ? usage.callsMissingUsage() > 0 && usage.inputUsdPerMillionTokens() != null
                        ? "n/a (incomplete: " + usage.callsMissingUsage() + " calls without token usage)"
                        : "n/a (no price table supplied)"
                : String.format(java.util.Locale.ROOT, "%.6f", usage.estimatedCostUsd()));
        if (usage.callsMissingUsage() > 0) {
            row(md, "Calls without token usage", String.valueOf(usage.callsMissingUsage()));
        }
        md.append("\n## Cases\n\n| Case | Family | Recommendation | Draft | Calls | Invariant failures |\n")
                .append("| --- | --- | --- | --- | ---: | --- |\n");
        for (EvalReport.CaseReport c : report.cases()) {
            md.append("| ").append(c.id()).append(" | ").append(c.family()).append(" | ")
                    .append(status(c.recommendationStatus(), c.recommendationRejection())).append(" | ")
                    .append(status(c.draftStatus(), c.draftRejection())).append(" | ").append(c.providerCalls())
                    .append(" | ").append(failures(c.violations())).append(" |\n");
        }
        md.append("\n## Content for grading (as delivered after the gate)\n\n");
        md.append("Model output about invented synthetic customers; use it to fill the rubric below.\n");
        for (EvalReport.CaseReport c : report.cases()) {
            EvalReport.Delivered d = c.delivered();
            if (d == null) {
                continue;
            }
            md.append("\n### ").append(c.id()).append(" (").append(c.family()).append(")\n\n");
            if (d.recommendation() != null) {
                md.append("- Recommendation: ").append(d.recommendation().action()).append(" / ")
                        .append(d.recommendation().templateIntent()).append(" (confidence ")
                        .append(d.recommendation().confidence()).append(")\n  - Rationale: ")
                        .append(oneLine(d.recommendation().rationale())).append('\n');
            }
            if (d.recommendationRefusal() != null) {
                md.append("- Recommendation refusal: ").append(oneLine(d.recommendationRefusal())).append('\n');
            }
            if (d.draft() != null) {
                md.append("- Draft (").append(d.draft().locale()).append("): ").append(oneLine(d.draft().body()))
                        .append("\n  - Rationale: ").append(oneLine(d.draft().rationale())).append("\n  - Evidence: ")
                        .append(d.draft().evidence()).append("; warnings: ").append(d.draft().warnings()).append('\n');
            }
            if (d.draftRefusal() != null) {
                md.append("- Draft refusal: ").append(oneLine(d.draftRefusal())).append('\n');
            }
        }
        md.append("\n## Human rubric\n\nRecommendation relevance, rationale usefulness, draft quality, factual grounding ")
                .append("and editability/tone are graded 1-5 by reviewers (see the rubric anchors in ")
                .append("`docs/verification/issue-98-ai-evaluation-verification.md`) and recorded in the JSON `rubric` ")
                .append("blocks. They are never combined with, or allowed to offset, the hard invariants above.\n");
        return md.toString();
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").replace("|", "\\|");
    }

    private static void row(StringBuilder md, String key, String value) {
        md.append("| ").append(key).append(" | ").append(value == null || value.isBlank() ? "-" : value).append(" |\n");
    }

    private static String status(String status, String detail) {
        return detail == null ? status : status + " (" + detail + ")";
    }

    private static String failures(Map<String, List<String>> violations) {
        return violations.isEmpty() ? "-" : String.join("; ", violations.keySet());
    }
}
