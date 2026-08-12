package com.libranet.membership.model;

/** What kind of member someone is — orthogonal to permissions (spec §10). */
public enum MembershipType {
    STUDENT, STAFF, FACULTY, RESEARCHER, ALUMNI, PUBLIC_MEMBER, GUEST, AUTHOR
}
