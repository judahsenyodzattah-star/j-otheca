package com.jotheca.membership.model;

/** Library-level role, scoped to one membership. Distinct from BOTH GlobalRole and MembershipType (spec §9/§10). */
public enum LibraryRole {
    LIBRARY_ADMIN,
    LIBRARIAN,
    PATRON
}
