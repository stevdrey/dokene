package io.github.stevdrey.dokene.customer.application;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PhoneNormalizer {
    public static final int INPUT_MAX_LENGTH = 64;
    private final PhoneNumberUtil phoneNumbers;

    public PhoneNormalizer() {
        this(PhoneNumberUtil.getInstance());
    }

    PhoneNormalizer(PhoneNumberUtil phoneNumbers) {
        this.phoneNumbers = Objects.requireNonNull(phoneNumbers, "Phone number utility is required");
    }

    public String normalize(String input, String region) {
        if (input == null || input.isBlank() || input.length() > INPUT_MAX_LENGTH || input.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid phone number");
        }
        if (region == null || !region.matches("[A-Za-z]{2}")) {
            throw new IllegalArgumentException("A two-letter country region is required");
        }
        String normalizedRegion = region.toUpperCase(Locale.ROOT);
        if (!phoneNumbers.getSupportedRegions().contains(normalizedRegion)) {
            throw new IllegalArgumentException("Unsupported phone region");
        }
        try {
            String normalizedInput = normalizeDigits(input);
            var parsed = phoneNumbers.parse(normalizedInput, normalizedRegion);
            if (!phoneNumbers.isValidNumberForRegion(parsed, normalizedRegion)) {
                throw new IllegalArgumentException("Invalid phone number");
            }
            return phoneNumbers.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException exception) {
            throw new IllegalArgumentException("Invalid phone number");
        }
    }

    private static String normalizeDigits(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        input.codePoints().forEach(cp -> {
            int digit = Character.digit(cp, 10);
            if (digit >= 0 && (cp < '0' || cp > '9')) {
                sb.append(digit);
            } else if (cp == 0xFF0B) {
                sb.append('+');
            } else {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }
}
