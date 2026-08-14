package com.libranet.permissions.model;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Granular staff permissions (audit P4/E19). Roles remain the template; overrides adjust a
 * LIBRARIAN row. LIBRARY_ADMIN is implicitly everything and is never overridable.
 */
public enum LibraryPerm {

    CATALOG_MANAGE("Catalog & copies", false),
    CIRCULATION_OPS("Desk circulation (issue / return / renew / holds shelf)", true),
    FINES_MANAGE("Fines: assess & settle at the desk", true),
    FINES_WAIVE("Waive fines (money-writing decision)", false),
    MEMBERS_MANAGE("Members: approve, reject, suspend, invite", false),
    POLICY_MANAGE("Loan policy editor", false),
    REVIEWS_MODERATE("Review & rating moderation", true),
    ISSUES_HANDLE("Support inbox (assign / resolve / request info)", true),
    REPORTS_VIEW("Dashboard, analytics & demand reports", true),
    BILLING_MANAGE("Plan, renewals & invoices", false);

    private final String label;
    private final boolean librarianDefault;

    LibraryPerm(String label, boolean librarianDefault) {
        this.label = label;
        this.librarianDefault = librarianDefault;
    }

    public String label() { return label; }

    public boolean isLibrarianDefault() { return librarianDefault; }

    public static Set<LibraryPerm> librarianDefaults() {
        EnumSet<LibraryPerm> set = EnumSet.noneOf(LibraryPerm.class);
        Arrays.stream(values()).filter(LibraryPerm::isLibrarianDefault).forEach(set::add);
        return set;
    }
}
