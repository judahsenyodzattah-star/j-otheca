package com.jotheca.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** SUPER_ADMIN provisioning of a tenant. Full self-service onboarding (plan→payment→approval) is Phase 3. */
public record CreateLibraryRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 30) String type,
        @Size(max = 100) String country,
        @Size(max = 100) String city,
        @Size(max = 300) String address,
        String description,
        @Size(max = 30) String joinPolicy,
        Boolean visibility) {}
