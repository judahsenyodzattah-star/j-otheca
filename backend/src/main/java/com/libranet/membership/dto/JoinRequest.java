package com.libranet.membership.dto;

import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Join a library (spec §13). Which fields matter depends on the library's join policy:
 * inviteCode for INVITATION_ONLY, verificationPayload for STUDENT_ONLY/STAFF_ONLY/CUSTOM.
 */
public record JoinRequest(
        @Size(max = 40) String inviteCode,
        @Size(max = 20) String membershipType,
        Map<String, String> verificationPayload) {}
