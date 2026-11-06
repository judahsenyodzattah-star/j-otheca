package com.jotheca.discovery.policy;

import java.util.Locale;

/** Validates and converts ISBN-10/ISBN-13 values to a canonical ISBN-13. */
public final class IsbnNormalizer {

    private IsbnNormalizer() {}

    /**
     * Returns {@code null} for a missing value and throws for a non-empty invalid value.
     * Hyphens and spaces are accepted; other punctuation is rejected.
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (value.matches("\\d{9}[\\dX]")) {
            if (!validIsbn10(value)) {
                throw invalid(raw);
            }
            return toIsbn13(value);
        }
        if (value.matches("\\d{13}")) {
            if (!validIsbn13(value)) {
                throw invalid(raw);
            }
            return value;
        }
        throw invalid(raw);
    }

    private static boolean validIsbn10(String value) {
        int sum = 0;
        for (int i = 0; i < value.length(); i++) {
            int digit = value.charAt(i) == 'X' ? 10 : value.charAt(i) - '0';
            sum += digit * (10 - i);
        }
        return sum % 11 == 0;
    }

    private static boolean validIsbn13(String value) {
        int sum = 0;
        for (int i = 0; i < value.length(); i++) {
            sum += (value.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return sum % 10 == 0;
    }

    private static String toIsbn13(String isbn10) {
        String prefix = "978" + isbn10.substring(0, 9);
        int sum = 0;
        for (int i = 0; i < prefix.length(); i++) {
            sum += (prefix.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        int check = (10 - (sum % 10)) % 10;
        return prefix + check;
    }

    private static IllegalArgumentException invalid(String raw) {
        return new IllegalArgumentException("Invalid ISBN-10 or ISBN-13: " + raw);
    }
}
