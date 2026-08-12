package com.libranet.media.repo;

import com.libranet.media.model.BookReview;
import com.libranet.media.model.BookReview.ReviewStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BookReviewRepository
        extends JpaRepository<BookReview, String>, JpaSpecificationExecutor<BookReview> {

    /** Any status — used for upsert/reactivation after soft-delete. */
    Optional<BookReview> findByTenantIdAndBookIdAndMembershipId(String tenantId, String bookId, String membershipId);

    Page<BookReview> findByTenantIdAndStatus(String tenantId, ReviewStatus status, Pageable pageable);

    Page<BookReview> findByTenantIdAndBookIdAndStatus(String tenantId, String bookId, ReviewStatus status, Pageable pageable);
    /** Dashboards: moderation backlog / my-review counts (any non-DELETED status). */
    long countByTenantIdAndStatus(String tenantId, ReviewStatus status);

    long countByTenantIdAndMembershipId(String tenantId, String membershipId);

}
