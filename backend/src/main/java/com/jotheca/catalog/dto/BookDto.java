package com.jotheca.catalog.dto;

import com.jotheca.catalog.model.Book;

/** Full book detail. Copies themselves are a separate staff endpoint; patrons see counts. */
public record BookDto(
        String id,
        String slug,
        String title,
        String subtitle,
        String author,
        String coAuthors,
        String isbn,
        String publisher,
        String publishedDate,
        String edition,
        String language,
        Integer pageCount,
        String description,
        String subjects,
        String categoryId,
        String categoryName,
        String coverRef,
        double ratingAvg,
        int ratingCount,
        boolean published,
        String authorUserId,
        String authorProfileId,
        Long salePriceMinor,
        String contactNote,
        long totalCopies,
        long availableCopies,
        String createdAt,
        String updatedAt) {

    public static BookDto of(Book b, String categoryName, BookAvailability availability) {
        BookAvailability a = availability != null ? availability : BookAvailability.NONE;
        return new BookDto(b.getId(), b.getSlug(), b.getTitle(), b.getSubtitle(), b.getAuthor(),
                b.getCoAuthors(), b.getIsbn(), b.getPublisher(), b.getPublishedDate(), b.getEdition(),
                b.getLanguage(), b.getPageCount(), b.getDescription(), b.getSubjects(), b.getCategoryId(),
                categoryName, b.getCoverRef(), b.getRatingAvg(), b.getRatingCount(), b.isPublished(),
                b.getAuthorUserId(), b.getAuthorProfileId(), b.getSalePriceMinor(), b.getContactNote(),
                a.total(), a.available(),
                b.getCreatedAt() == null ? null : b.getCreatedAt().toString(),
                b.getUpdatedAt() == null ? null : b.getUpdatedAt().toString());
    }
}
