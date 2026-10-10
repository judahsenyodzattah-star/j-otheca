package com.jotheca.media.repo;

import com.jotheca.media.model.BookRating;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookRatingRepository extends JpaRepository<BookRating, String> {

    Optional<BookRating> findByTenantIdAndBookIdAndMembershipId(String tenantId, String bookId, String membershipId);
    /** Recommendation profile: everything this member rated (≥3 stars signal taste). */
    java.util.List<BookRating> findByTenantIdAndMembershipId(String tenantId, String membershipId);

    /** Aggregate pair for re-denormalizing books.rating_avg / rating_count (scalar queries: unambiguous). */
    @Query("select coalesce(avg(r.stars), 0.0) from BookRating r"
            + " where r.tenantId = :tenantId and r.bookId = :bookId")
    Double avgStars(@Param("tenantId") String tenantId, @Param("bookId") String bookId);

    @Query("select count(r) from BookRating r where r.tenantId = :tenantId and r.bookId = :bookId")
    long countFor(@Param("tenantId") String tenantId, @Param("bookId") String bookId);
}
