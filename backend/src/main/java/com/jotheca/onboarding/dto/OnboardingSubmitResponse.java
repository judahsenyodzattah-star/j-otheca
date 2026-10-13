package com.jotheca.onboarding.dto;

/** After submit: the request + where to pay. */
public record OnboardingSubmitResponse(String requestId, String status, String planCode,
                                       String paymentReference, String authorizationUrl,
                                       long amountMinor, String currency, String provider) {}
