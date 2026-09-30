package io.github.stevdrey.dokene.ai.domain;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Authoritative fields an evidence citation may refer to, kept separate so a value is only
 * accepted for the fact type named by its label (a customer named "Televisor" does not ground
 * a purchase citation).
 */
public record DraftGroundingContext(String customerName, String notes, List<String> purchaseDescriptions,
        List<String> purchaseDates, String followUpStatus, String tenantDate) {

    public DraftGroundingContext {
        purchaseDescriptions = List.copyOf(Objects.requireNonNullElse(purchaseDescriptions, List.of()));
        purchaseDates = List.copyOf(Objects.requireNonNullElse(purchaseDates, List.of()));
    }

    /**
     * Closed evidence label vocabulary (also advertised to the model in the schema and prompt).
     */
    public static final String ALLOWED_LABELS_DESCRIPTION =
            "Compra, Producto, Artículo, Fecha de compra, Nombre, Cliente, Notas, Estado de seguimiento";

    /**
     * Text that can legitimately authorize an offer, price or discount claim: only free-form
     * customer notes and purchase descriptions, never identity fields such as names.
     */
    String offerBearingText() {
        StringBuilder sb = new StringBuilder();
        if (notes != null) {
            sb.append(notes).append(' ');
        }
        purchaseDescriptions.forEach(description -> sb.append(description).append(' '));
        return sb.toString();
    }

    /** How an evidence value must match the authoritative field it cites. */
    enum MatchMode { EXACT, WHOLE_WORD, SUBSTRING }

    record EvidenceSource(List<String> values, MatchMode mode) {
        boolean matches(String lowerCaseValue) {
            return values.stream().map(v -> v.toLowerCase(Locale.ROOT).strip()).anyMatch(candidate -> switch (mode) {
                case EXACT -> candidate.equals(lowerCaseValue);
                case WHOLE_WORD -> java.util.regex.Pattern
                        .compile("(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(lowerCaseValue) + "(?![\\p{L}\\p{N}])")
                        .matcher(candidate).find();
                case SUBSTRING -> candidate.contains(lowerCaseValue);
            });
        }
    }

    /**
     * Returns the authoritative source a label refers to, or {@code null} when the label is not
     * part of the closed vocabulary (a recognised type with no data yields an empty source).
     * Scalar fields (dates, status) require equality, names a whole-word match, and free-form
     * fields (notes, purchases) a substring match.
     */
    EvidenceSource sourceForLabel(String label) {
        String normalized = label.toLowerCase(Locale.ROOT).strip();
        return switch (normalized) {
            case "fecha de compra" -> new EvidenceSource(purchaseDates, MatchMode.EXACT);
            case "notas" -> new EvidenceSource(notes == null ? List.of() : List.of(notes), MatchMode.SUBSTRING);
            case "compra", "compra reciente", "producto", "nombre del producto", "artículo", "articulo" ->
                    new EvidenceSource(purchaseDescriptions, MatchMode.SUBSTRING);
            case "nombre", "cliente", "nombre del cliente" ->
                    new EvidenceSource(customerName == null ? List.of() : List.of(customerName), MatchMode.WHOLE_WORD);
            case "estado de seguimiento" ->
                    new EvidenceSource(followUpStatus == null ? List.of() : List.of(followUpStatus), MatchMode.EXACT);
            default -> null;
        };
    }
}
