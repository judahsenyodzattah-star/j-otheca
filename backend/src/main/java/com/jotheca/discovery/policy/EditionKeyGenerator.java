package com.jotheca.discovery.policy;

/** Conservative edition identity generation for a future global projection. */
public final class EditionKeyGenerator {

    private EditionKeyGenerator() {}

    /**
     * ISBN is the only cross-library grouping signal in this foundation.
     * Missing ISBN values remain source-specific so similar titles are never
     * merged merely because their descriptive metadata happens to match.
     */
    public static EditionKey forRecord(String sourceBookId, String isbn) {
        if (sourceBookId == null || sourceBookId.isBlank()) {
            throw new IllegalArgumentException("sourceBookId is required");
        }
        String normalized = IsbnNormalizer.normalize(isbn);
        if (normalized == null) {
            return new EditionKey("source:" + sourceBookId.trim(), false);
        }
        return new EditionKey("isbn:" + normalized, true);
    }

    public record EditionKey(String value, boolean isbnBacked) {}
}
