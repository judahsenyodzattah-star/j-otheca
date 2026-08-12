package com.libranet.onboarding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateOnboardingRequest(
        @NotBlank @Size(max = 200) String libraryName,
        @NotBlank @Size(max = 30) String type,
        @Size(max = 100) String country,
        @Size(max = 100) String city,
        @Size(max = 300) String address,
        String description,
        @NotBlank @Size(max = 20) String planCode) {}
