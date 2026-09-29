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
        List<String> purchaseDates) {

    public DraftGroundingContext {
        purchaseDescriptions = List.copyOf(Objects.requireNonNullElse(purchaseDescriptions, List.of()));
        purchaseDates = List.copyOf(Objects.requireNonNullElse(purchaseDates, List.of()));
    }

    /**
     * Returns the authoritative values a lowercase label refers to, or {@code null} when the
     * label is not a recognised fact type (a recognised type with no data yields an empty list).
     */
    List<String> sourcesForLabel(String label) {
        String normalized = label.toLowerCase(Locale.ROOT);
        if (normalized.contains("fecha")) {
            return purchaseDates;
        }
        if (normalized.contains("nota")) {
            return notes == null ? List.of() : List.of(notes);
        }
        if (normalized.contains("nombre") || normalized.contains("cliente")) {
            return customerName == null ? List.of() : List.of(customerName);
        }
        if (normalized.contains("compra") || normalized.contains("producto")
                || normalized.contains("artículo") || normalized.contains("articulo")) {
            return purchaseDescriptions;
        }
        return null;
    }
}
