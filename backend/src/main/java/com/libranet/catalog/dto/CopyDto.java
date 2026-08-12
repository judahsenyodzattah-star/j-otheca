package com.libranet.catalog.dto;

import com.libranet.catalog.model.BookCopy;

/** Staff view of a physical copy, with its resolved human-readable shelf address. */
public record CopyDto(
        String id,
        String bookId,
        String barcode,
        String accessionNumber,
        String status,
        String branchId,
        String branchName,
        String shelfLocationId,
        String shelfLocationDisplay,
        String notes,
        String createdAt) {

    public static CopyDto of(BookCopy c, String branchName, String shelfDisplay) {
        return new CopyDto(c.getId(), c.getBookId(), c.getBarcode(), c.getAccessionNumber(),
                c.getStatus().name(), c.getBranchId(), branchName, c.getShelfLocationId(), shelfDisplay,
                c.getNotes(), c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
    }
}
