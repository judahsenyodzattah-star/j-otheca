package com.jotheca.publishing.repo;

import com.jotheca.publishing.model.BookInterest;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookInterestRepository extends JpaRepository<BookInterest, String> {

    Optional<BookInterest> findByTenantIdAndBookIdAndMembershipId(String tenantId, String bookId,
                                                                  String membershipId);

    long countByTenantIdAndBookId(String tenantId, String bookId);

    /** Rows of (bookId, count) for list responses. */
    @Query("SELECT i.bookId, COUNT(i) FROM BookInterest i "
            + "WHERE i.tenantId = :tenantId AND i.bookId IN :bookIds GROUP BY i.bookId")
    List<Object[]> countByBookGrouped(@Param("tenantId") String tenantId,
                                      @Param("bookIds") Collection<String> bookIds);

    List<BookInterest> findByTenantIdAndBookId(String tenantId, String bookId);
    /** Recommendation profile: titles this member flagged. */
    List<BookInterest> findByTenantIdAndMembershipId(String tenantId, String membershipId);
    /** Dashboards: demand totals + top-demanded titles (bookId, demand pairs, page-limited). */
    long countByTenantId(String tenantId);

    long countByTenantIdAndMembershipId(String tenantId, String membershipId);

    /** E17: studio aggregates across libraries — profiles are user-global, never tenant-bound. */
    List<BookInterest> findByBookIdIn(java.util.Collection<String> bookIds);

    @Query("SELECT i.bookId, COUNT(i) FROM BookInterest i WHERE i.tenantId = :tenantId "
            + "GROUP BY i.bookId ORDER BY COUNT(i) DESC")
    List<Object[]> topDemanded(@Param("tenantId") String tenantId, org.springframework.data.domain.Pageable pageable);

}
