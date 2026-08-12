package com.libranet.tenancy.model;

public enum LibraryStatus {
    PENDING_APPROVAL,   // registered, awaiting SUPER_ADMIN verification/approval (spec §11)
    ACTIVE,
    SUSPENDED
}
