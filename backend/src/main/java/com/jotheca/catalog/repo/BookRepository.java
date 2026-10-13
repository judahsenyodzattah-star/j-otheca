package com.jotheca.catalog.repo;

import com.jotheca.catalog.model.Book;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BookRepository extends JpaRepository<Book, String>, JpaSpecificationExecutor<Book> {

    Optional<Book> findByTenantIdAndIsbn(String tenantId, String isbn);

    List<Book> findByTenantIdAndCoverRef(String tenantId, String coverRef);

    Optional<Book> findByTenantIdAndSlug(String tenantId, String slug);

    /** E18/E17: author-profile linkage read in /me and public contexts (no tenant filter armed). */
    List<Book> findByAuthorProfileId(String authorProfileId);

    List<Book> findByAuthorProfileIdAndPublishedTrue(String authorProfileId);

    long countByTenantIdAndCategoryId(String tenantId, String categoryId);

    long countByTenantId(String tenantId);
    /** Dashboard: highest-rated published titles with a minimum number of ratings. */
    org.springframework.data.domain.Page<Book> findByTenantIdAndPublishedTrueAndRatingCountGreaterThanEqualOrderByRatingAvgDescRatingCountDesc(
            String tenantId, int minRatingCount, org.springframework.data.domain.Pageable pageable);

}
