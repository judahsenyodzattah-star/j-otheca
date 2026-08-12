package com.libranet.identity.dto;

import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(@Size(min = 1, max = 200) String fullName, @Size(max = 40) String phone) {}
