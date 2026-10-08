package io.github.stevdrey.dokene.ai.provider.fake;

import io.github.stevdrey.dokene.ai.application.AiCompletionStatus;
import io.github.stevdrey.dokene.ai.application.AiDraftRequest;
import io.github.stevdrey.dokene.ai.application.AiDraftResponse;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.application.AiRecommendationResponse;
import io.github.stevdrey.dokene.ai.application.AiTokenUsage;
import io.github.stevdrey.dokene.ai.domain.ActionRecommendation;
import io.github.stevdrey.dokene.ai.domain.DraftOutcome;
import io.github.stevdrey.dokene.ai.domain.DraftVariables;
import io.github.stevdrey.dokene.ai.domain.MessageDraft;
import io.github.stevdrey.dokene.ai.domain.NoDraft;
import io.github.stevdrey.dokene.ai.domain.NoDraftReason;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendationReason;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.ai.domain.RecommendationOutcome;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic in-memory fake provider for local development, staging environments,
 * and test profiles where an external OpenAI API key is not configured.
 */
public final class DefaultFakeAiProvider implements AiProvider {

    @Override
    public AiRecommendationResponse recommend(AiRecommendationRequest request) {
        Objects.requireNonNull(request, "Request is required");
        RecommendationOutcome outcome = buildOutcome(request);
        AiInvocationMetadata metadata = new AiInvocationMetadata(
                "fake-provider",
                "deterministic-fake",
                "fake-req-1",
                Duration.ofMillis(5),
                new AiTokenUsage(0L, 0L),
                AiCompletionStatus.SUCCEEDED);
        return new AiRecommendationResponse(outcome, metadata);
    }

    @Override
    public AiDraftResponse draft(AiDraftRequest request) {
        Objects.requireNonNull(request, "Request is required");
        DraftOutcome outcome = buildDraftOutcome(request);
        AiInvocationMetadata metadata = new AiInvocationMetadata(
                "fake-provider",
                "deterministic-fake",
                "fake-draft-1",
                Duration.ofMillis(5),
                new AiTokenUsage(0L, 0L),
                AiCompletionStatus.SUCCEEDED);
        return new AiDraftResponse(outcome, metadata);
    }

    private RecommendationOutcome buildOutcome(AiRecommendationRequest request) {
        if (request.context() == null || request.context().trusted() == null) {
            return new NoRecommendation(
                    NoRecommendationReason.INSUFFICIENT_HISTORY,
                    "No se proporcionó contexto confiable",
                    RecommendationConfidence.of(0.5));
        }
        List<SemanticAction> allowed = request.context().trusted().allowedActions();
        if (allowed == null || allowed.isEmpty()) {
            return new NoRecommendation(
                    NoRecommendationReason.NO_RELEVANT_OFFER,
                    "No hay acciones permitidas para el cliente",
                    RecommendationConfidence.of(0.5));
        }

        SemanticAction action = allowed.contains(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP)
                ? SemanticAction.REPEAT_PURCHASE_FOLLOW_UP
                : allowed.getFirst();
        SemanticTemplateIntent intent = action == SemanticAction.REPEAT_PURCHASE_FOLLOW_UP
                ? SemanticTemplateIntent.REPEAT_PURCHASE
                : SemanticTemplateIntent.GENERAL_FOLLOW_UP;

        return new ActionRecommendation(
                action,
                intent,
                "Recomendación automática de seguimiento según la cadencia de compra",
                RecommendationConfidence.of(0.85),
                DraftVariables.empty());
    }

    private DraftOutcome buildDraftOutcome(AiDraftRequest request) {
        if (request.context() == null || request.context().customerContext() == null) {
            return new NoDraft(
                    NoDraftReason.MISSING_TRUSTED_FACTS,
                    "No se proporcionó contexto para redactar el borrador",
                    RecommendationConfidence.of(0.5));
        }
        var cust = request.context().customerContext();
        var untrusted = cust.untrusted();
        String notes = untrusted != null ? untrusted.notes() : null;
        if (notes != null && (notes.contains("IGNORE ALL PREVIOUS") || notes.contains("<script>") || notes.contains("OVERRIDE POLICY"))) {
            return new NoDraft(
                    NoDraftReason.SAFETY_VIOLATION,
                    "Se detectaron instrucciones sospechosas en las notas del cliente",
                    RecommendationConfidence.of(0.95));
        }

        String requestedLocale = request.context().businessFacts() != null
                ? request.context().businessFacts().preferredLocale() : MessageDraft.DEFAULT_LOCALE;
        if (!MessageDraft.DEFAULT_LOCALE.equalsIgnoreCase(requestedLocale.strip())) {
            return new NoDraft(
                    NoDraftReason.UNSUPPORTED_ACTION,
                    "El proveedor simulado solo admite el idioma " + MessageDraft.DEFAULT_LOCALE,
                    RecommendationConfidence.of(0.5));
        }

        String displayName = untrusted != null ? untrusted.displayName() : "Cliente";
        String businessName = request.context().businessFacts() != null
                ? request.context().businessFacts().businessName()
                : "Dokene";

        List<String> purchases = untrusted != null ? untrusted.purchaseDescriptions() : List.of();
        String lastProduct = !purchases.isEmpty() ? purchases.getFirst() : null;

        SemanticAction action = request.context().action();
        SemanticTemplateIntent intent = request.context().templateIntent();

        String body;
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("customer_name", displayName);
        vars.put("business_name", businessName);
        List<String> evidence = new ArrayList<>();
        evidence.add(boundEvidence("Nombre: " + displayName));

        if (action == SemanticAction.REPEAT_PURCHASE_FOLLOW_UP && lastProduct != null) {
            String safeProduct = truncateCodePoints(lastProduct, 180);
            body = "Hola " + displayName + ", te saludamos de " + businessName + ". Esperamos que hayas disfrutado tu compra de " + safeProduct + ". ¿Te gustaría ordenar nuevamente?";
            vars.put("product", safeProduct);
            evidence.add(boundEvidence("Compra: " + safeProduct));
        } else {
            body = "Hola " + displayName + ", te saludamos de " + businessName + ". Queríamos saber cómo te ha ido y si podemos ayudarte en algo.";
        }

        return new MessageDraft(
                action,
                intent,
                body,
                DraftVariables.of(vars),
                "es-419",
                evidence,
                List.of(),
                "Borrador determinista de seguimiento en español latinoamericano",
                RecommendationConfidence.of(0.85));
    }

    private static String boundEvidence(String text) {
        if (text == null) {
            return "";
        }
        return truncateCodePoints(text, MessageDraft.MAX_METADATA_ITEM_LENGTH);
    }

    private static String truncateCodePoints(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
    }
}
