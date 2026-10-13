package com.jotheca.catalog.dto;

import com.jotheca.catalog.model.Book;

/** Catalog search/list row. */
public record BookSummaryDto(
        String id,
        String slug,
        String title,
        String subtitle,
        String author,
        String isbn,
        String publisher,
        String publishedDate,
        String language,
        String categoryId,
        String categoryName,
        String coverRef,
        double ratingAvg,
        int ratingCount,
        boolean published,
        long totalCopies,
        long availableCopies) {

    public static BookSummaryDto of(Book b, String categoryName, BookAvailability availability) {
        BookAvailability a = availability != null ? availability : BookAvailability.NONE;
        return new BookSummaryDto(b.getId(), b.getSlug(), b.getTitle(), b.getSubtitle(), b.getAuthor(),
                b.getIsbn(), b.getPublisher(), b.getPublishedDate(), b.getLanguage(), b.getCategoryId(),
                categoryName, b.getCoverRef(), b.getRatingAvg(), b.getRatingCount(), b.isPublished(),
                a.total(), a.available());
    }
}
