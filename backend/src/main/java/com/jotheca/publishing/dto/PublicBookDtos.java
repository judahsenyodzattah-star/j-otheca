package com.jotheca.publishing.dto;

import com.jotheca.catalog.model.Book;
import com.jotheca.tenancy.model.Library;

public final class PublicBookDtos {

    private PublicBookDtos() {}

    /** Public catalog row — no internal ids beyond the slug, no staff data. */
    public record PublicBookSummaryDto(String slug, String title, String subtitle, String author,
                                       String isbn, String publisher, String publishedDate, String language,
                                       String coverRef, String categoryName,
                                       double ratingAvg, int ratingCount,
                                       long availableCopies, boolean forSale, String shareUrl) {
        public static PublicBookSummaryDto of(Book b, Library lib, String categoryName, long available,
                                              String publicBaseUrl) {
            return new PublicBookSummaryDto(b.getSlug(), b.getTitle(), b.getSubtitle(), b.getAuthor(),
                    b.getIsbn(), b.getPublisher(), b.getPublishedDate(), b.getLanguage(), b.getCoverRef(),
                    categoryName, b.getRatingAvg(), b.getRatingCount(), available,
                    b.getSalePriceMinor() != null,
                    PublicBookDtos.shareUrl(publicBaseUrl, lib, b));
        }
    }

    /** The public book page (spec §24: /book/my-book). */
    public record PublicBookDto(String slug, String title, String subtitle, String author, String coAuthors,
                                String isbn, String publisher, String publishedDate, String edition,
                                String language, Integer pageCount, String description, String subjects,
                                String categoryName, String coverRef, double ratingAvg, int ratingCount,
                                long totalCopies, long availableCopies, long interestedCount,
                                Long salePriceMinor, String currency, String contactNote,
                                String librarySlug, String libraryName, String libraryCity,
                                boolean libraryOpenMembership, String shareUrl) {
        public static PublicBookDto of(Book b, Library lib, String categoryName, long total, long available,
                                       long interested, String publicBaseUrl) {
            return new PublicBookDto(b.getSlug(), b.getTitle(), b.getSubtitle(), b.getAuthor(), b.getCoAuthors(),
                    b.getIsbn(), b.getPublisher(), b.getPublishedDate(), b.getEdition(), b.getLanguage(),
                    b.getPageCount(), b.getDescription(), b.getSubjects(), categoryName, b.getCoverRef(),
                    b.getRatingAvg(), b.getRatingCount(), total, available, interested,
                    b.getSalePriceMinor(), "GHS", b.getContactNote(),
                    lib.getSlug(), lib.getName(), lib.getCity(),
                    "PUBLIC".equals(lib.getJoinPolicy().name()),
                    PublicBookDtos.shareUrl(publicBaseUrl, lib, b));
        }
    }

    static String shareUrl(String baseUrl, Library lib, Book b) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "/book/" + lib.getSlug() + "/" + b.getSlug();
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/book/" + lib.getSlug() + "/" + b.getSlug();
    }
}
