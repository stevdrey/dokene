package io.github.stevdrey.dokene.customer.application;

import io.github.stevdrey.dokene.customer.domain.CustomerStatus;
import java.util.Locale;

public record CustomerSearch(Status status, String name, String normalizedPhone, CustomerCursor cursor, int limit) {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    public CustomerSearch {
        if (status == null || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Invalid customer search");
        }
        if (name != null) {
            name = name.strip();
            if (name.isEmpty() || name.length() > 160 || name.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid customer name filter");
            }
        }
    }

    public enum Status {
        ACTIVE, ARCHIVED, ALL;

        public static Status parse(String value) {
            if (value == null || value.isBlank()) {
                return ACTIVE;
            }
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid customer status filter");
            }
        }

        public CustomerStatus customerStatus() {
            return this == ALL ? null : CustomerStatus.valueOf(name());
        }
    }
}
