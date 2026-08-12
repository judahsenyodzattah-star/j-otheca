package com.libranet.onboarding.repo;

import com.libranet.onboarding.model.OnboardingRequest;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingRequestRepository extends JpaRepository<OnboardingRequest, String> {
    List<OnboardingRequest> findByApplicantUserIdOrderByCreatedAtDesc(String applicantUserId);
    Page<OnboardingRequest> findByStatus(OnboardingRequest.Status status, Pageable pageable);
}
