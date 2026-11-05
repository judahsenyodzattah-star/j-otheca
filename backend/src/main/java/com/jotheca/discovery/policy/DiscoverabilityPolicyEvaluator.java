package com.jotheca.discovery.policy;

import com.jotheca.tenancy.model.CatalogVisibility;
import com.jotheca.tenancy.model.LibraryStatus;

/**
 * Pure global-discovery eligibility rules.
 *
 * <p>This foundation deliberately requires the explicit book flag even for a
 * PUBLIC catalog. The future administrator workflow will be responsible for
 * granting that opt-in; this avoids publishing existing records accidentally
 * while the workflow is not yet available.</p>
 */
public final class DiscoverabilityPolicyEvaluator {

    private DiscoverabilityPolicyEvaluator() {}

    public static boolean isDiscoverable(LibraryStatus status,
                                         boolean directoryVisible,
                                         CatalogVisibility catalogVisibility,
                                         boolean published,
                                         boolean globalDiscoverable) {
        return status == LibraryStatus.ACTIVE
                && directoryVisible
                && catalogVisibility != null
                && catalogVisibility != CatalogVisibility.MEMBERS_ONLY
                && published
                && globalDiscoverable;
    }
}
