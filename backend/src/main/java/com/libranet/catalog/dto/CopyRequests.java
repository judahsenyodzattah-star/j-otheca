package com.libranet.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Copy-related payloads. Kept as nested records — Java allows one public outer type per file. */
public final class CopyRequests {

    private CopyRequests() {}

    /** Register a new physical copy. Barcode optional → generated as {branchCode}-{seq}. */
    public record CreateCopyRequest(
            @Size(max = 60) String barcode,
            @Size(max = 60) String accessionNumber,
            @Size(max = 36) String branchId,
            @Size(max = 36) String shelfLocationId,
            @Size(max = 1000) String notes) {}

    /** Status transitions staff may make outside circulation flows (circulation owns BORROWED). */
    public record UpdateCopyStatusRequest(@NotNull String status, @Size(max = 1000) String notes) {}

    public record UpdateCopyRequest(
            @Size(max = 60) @NotBlank String barcode,
            @Size(max = 60) String accessionNumber,
            @Size(max = 36) String branchId,
            @Size(max = 36) String shelfLocationId,
            @Size(max = 1000) String notes) {}
}
