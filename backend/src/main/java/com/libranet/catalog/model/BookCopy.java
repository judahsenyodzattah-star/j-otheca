package com.libranet.catalog.model;

import com.libranet.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** One physical unit of a {@link Book}: the thing that is scanned, shelved, borrowed and returned. */
@Entity
@Table(name = "book_copies")
public class BookCopy extends TenantScopedEntity {

    @Column(name = "book_id", nullable = false, length = 36)
    private String bookId;

    /** Scannable identifier, e.g. AC-000041. Unique per tenant. */
    @Column(nullable = false, length = 60)
    private String barcode;

    /** Classic accession register number. Unique per tenant when present. */
    @Column(name = "accession_number", length = 60)
    private String accessionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CopyStatus status = CopyStatus.AVAILABLE;

    @Column(name = "branch_id", length = 36)
    private String branchId;

    @Column(name = "shelf_location_id", length = 36)
    private String shelfLocationId;

    @Column(length = 1000)
    private String notes;

    public String getBookId() { return bookId; }
    public void setBookId(String bookId) { this.bookId = bookId; }
    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }
    public String getAccessionNumber() { return accessionNumber; }
    public void setAccessionNumber(String accessionNumber) { this.accessionNumber = accessionNumber; }
    public CopyStatus getStatus() { return status; }
    public void setStatus(CopyStatus status) { this.status = status; }
    public String getBranchId() { return branchId; }
    public void setBranchId(String branchId) { this.branchId = branchId; }
    public String getShelfLocationId() { return shelfLocationId; }
    public void setShelfLocationId(String shelfLocationId) { this.shelfLocationId = shelfLocationId; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
