package io.github.stevdrey.dokene.customer.application;

import io.github.stevdrey.dokene.customer.domain.Customer;
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
            name = normalizeNameFilter(name);
        }
    }

    private static String normalizeNameFilter(String value) {
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid customer name filter");
        }
        for (int index = 0; index < value.length(); index++) {
            char codeUnit = value.charAt(index);
            if (Character.isHighSurrogate(codeUnit)) {
                if (index + 1 == value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("Invalid customer name filter");
                }
                index++;
            } else if (Character.isLowSurrogate(codeUnit)) {
                throw new IllegalArgumentException("Invalid customer name filter");
            }
        }

        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!isWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }

        String normalized = value.substring(start, end);
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > Customer.DISPLAY_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid customer name filter");
        }
        return normalized;
    }

    private static boolean isWhitespace(int codePoint) {
        return codePoint == 0x0085 || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
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
