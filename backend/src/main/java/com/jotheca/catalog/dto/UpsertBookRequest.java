package com.jotheca.catalog.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Staff payload for creating/updating a title. ISBN is normalized server-side before uniqueness checks. */
public record UpsertBookRequest(
        @NotBlank @Size(max = 500) String title,
        @Size(max = 500) String subtitle,
        @NotBlank @Size(max = 300) String author,
        @Size(max = 500) String coAuthors,
        @Size(max = 20) String isbn,
        @Size(max = 200) String publisher,
        @Size(max = 10) String publishedDate,
        @Size(max = 60) String edition,
        @Size(max = 60) String language,
        @Min(1) @Max(100000) Integer pageCount,
        @Size(max = 20000) String description,
        @Size(max = 500) String subjects,
        @Size(max = 36) String categoryId,
        @Size(max = 500) String coverRef,
        Boolean published,
        @Size(max = 36) String authorUserId,
        @Size(max = 36) String authorProfileId,   // E18: link to the author's capability profile
        @Min(0) Long salePriceMinor,
        @Size(max = 300) String contactNote) {}
