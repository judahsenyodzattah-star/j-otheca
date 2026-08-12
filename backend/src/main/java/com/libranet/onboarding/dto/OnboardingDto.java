package com.libranet.onboarding.dto;

import com.libranet.onboarding.model.OnboardingRequest;
import java.time.Instant;

public record OnboardingDto(String id, String libraryName, String type, String city, String country,
                            String planCode, String status, String rejectionReason,
                            String librarySlug, Instant createdAt) {

    public static OnboardingDto from(OnboardingRequest r, String librarySlug) {
        return new OnboardingDto(r.getId(), r.getLibraryName(), r.getType().name(), r.getCity(), r.getCountry(),
                r.getPlanCode(), r.getStatus().name(), r.getRejectionReason(), librarySlug, r.getCreatedAt());
    }
}
