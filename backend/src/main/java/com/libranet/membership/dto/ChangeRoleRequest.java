package com.libranet.membership.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangeRoleRequest(@NotBlank @Size(max = 20) String role) {}
