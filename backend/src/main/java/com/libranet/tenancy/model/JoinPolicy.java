package com.libranet.tenancy.model;

/** How a library admits members (spec §13). Libraries configure this themselves. */
public enum JoinPolicy {
    PUBLIC,
    APPROVAL_REQUIRED,
    STUDENT_ONLY,
    STAFF_ONLY,
    INVITATION_ONLY,
    DOMAIN_VERIFIED,
    CUSTOM
}
