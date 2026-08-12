package com.libranet.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 10, max = 100, message = "Password must be at least 10 characters") String password,
        @NotBlank @Size(max = 200) String fullName,
        @Size(max = 40) String phone) {}
