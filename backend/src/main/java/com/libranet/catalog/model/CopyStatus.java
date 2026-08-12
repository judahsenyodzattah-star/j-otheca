package com.libranet.catalog.model;

/** Lifecycle of a physical copy (spec §19). Removal is a status — never a hard delete of loan history. */
public enum CopyStatus {
    AVAILABLE,
    BORROWED,
    RESERVED,
    LOST,
    DAMAGED,
    MAINTENANCE,
    REMOVED
}
