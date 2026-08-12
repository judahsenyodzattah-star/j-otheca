package com.libranet.identity.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** {@code devVerificationToken} is populated ONLY in the dev profile so the demo can verify emails without SMTP. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterResponse(UserDto user, String devVerificationToken) {}
