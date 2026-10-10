package com.jotheca.identity.dto;

public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds,
                            String refreshToken, UserDto user) {}
