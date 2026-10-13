package com.jotheca.catalog.repo;

import com.jotheca.catalog.model.BookCopy;
import com.jotheca.catalog.model.CopyStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookCopyRepository extends JpaRepository<BookCopy, String> {

    List<BookCopy> findByTenantIdAndBookIdOrderByBarcodeAsc(String tenantId, String bookId);

    List<BookCopy> findByTenantIdAndBarcodeStartingWith(String tenantId, String barcodePrefix);

    Optional<BookCopy> findByTenantIdAndBarcode(String tenantId, String barcode);

    Optional<BookCopy> findByTenantIdAndAccessionNumber(String tenantId, String accessionNumber);

    long countByTenantIdAndBookId(String tenantId, String bookId);
    /** Tenant dashboard: physical holdings count. */
    long countByTenantId(String tenantId);

    long countByTenantIdAndBookIdAndStatus(String tenantId, String bookId, CopyStatus status);

    long countByTenantIdAndBranchId(String tenantId, String branchId);

    long countByTenantIdAndShelfLocationId(String tenantId, String shelfLocationId);

    /** Rows of (bookId, status, count) used to decorate book list/detail responses. */
    @Query("SELECT c.bookId, c.status, COUNT(c) FROM BookCopy c "
            + "WHERE c.tenantId = :tenantId AND c.bookId IN :bookIds GROUP BY c.bookId, c.status")
    List<Object[]> countByBookGrouped(@Param("tenantId") String tenantId,
                                      @Param("bookIds") Collection<String> bookIds);

    /**
     * Pessimistic row lock for the desk checkout path (audit 2026-10-10): SELECT ... FOR UPDATE so two
     * concurrent issues of the same copy serialize and the loser reads BORROWED → clean 409, never a
     * double-issue. Defined via @Query because "ForUpdate" is not a derived-query keyword.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from BookCopy c where c.id = :id")
    Optional<BookCopy> findByIdForUpdate(@Param("id") String id);
}
