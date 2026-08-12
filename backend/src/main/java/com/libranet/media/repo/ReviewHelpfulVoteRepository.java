package com.libranet.media.repo;

import com.libranet.media.model.ReviewHelpfulVote;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewHelpfulVoteRepository extends JpaRepository<ReviewHelpfulVote, String> {

    Optional<ReviewHelpfulVote> findByTenantIdAndReviewIdAndMembershipId(
            String tenantId, String reviewId, String membershipId);
}
