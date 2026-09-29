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

    /**
     * Returns the authoritative values a label refers to, or {@code null} when the label is not
     * part of the closed vocabulary (a recognised type with no data yields an empty list).
     */
    List<String> sourcesForLabel(String label) {
        String normalized = label.toLowerCase(Locale.ROOT).strip();
        return switch (normalized) {
            case "fecha de compra" -> purchaseDates;
            case "notas" -> notes == null ? List.of() : List.of(notes);
            case "compra", "compra reciente", "producto", "nombre del producto", "artículo", "articulo" -> purchaseDescriptions;
            case "nombre", "cliente", "nombre del cliente" -> customerName == null ? List.of() : List.of(customerName);
            case "estado de seguimiento" -> followUpStatus == null ? List.of() : List.of(followUpStatus);
            default -> null;
        };
    }
}
