package com.jotheca.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jotheca.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextProvisionalScopeTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void provisionalDatabaseScopeCannotAuthorizeTenantWork() {
        TenantContext.setProvisionalDatabaseTenant("library-a");

        assertThat(TenantContext.get()).isNull();
        assertThatThrownBy(TenantContext::require)
                .hasMessageContaining("No tenant context resolved");
        assertThatThrownBy(TenantContext::requireLibraryId)
                .hasMessageContaining("No tenant context resolved");
    }

    @Test
    void provisionalScopeCannotOverwriteAuthorizedContext() {
        TenantContext.set(new TenantContext.Current("library-a", "LIB-A", "membership-a", "PATRON"));

        assertThatThrownBy(() -> TenantContext.setProvisionalDatabaseTenant("library-b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authorized context");
        assertThat(TenantContext.requireLibraryId()).isEqualTo("library-a");
    }

    @Test
    void authorizedContextReplacesProvisionalDatabaseScope() {
        TenantContext.setProvisionalDatabaseTenant("library-a");
        TenantContext.set(new TenantContext.Current("library-a", "LIB-A", "membership-a", "PATRON"));

        assertThat(TenantContext.get().membershipId()).isEqualTo("membership-a");
        assertThat(TenantContext.requireLibraryId()).isEqualTo("library-a");
    }

    @Test
    void clearRemovesAuthorizedAndProvisionalState() {
        TenantContext.setProvisionalDatabaseTenant("library-a");
        TenantContext.clear();

        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void failedMembershipDiagnosticSummaryContainsOnlyAllowlistedStates() {
        TenantContext.setProvisionalDatabaseTenant("library-a");
        TenantContext.beginMembershipDiagnostics(true);

        assertThat(TenantContext.membershipDiagnosticSummary(false))
                .isEqualTo("provisional_scope_present=true transaction_active=true "
                        + "tenant_arming_attempted=false tenant_arming_succeeded=false membership_found=false");
    }

    @Test
    void diagnosticStateIsClearedAfterRequestCleanup() {
        TenantContext.setProvisionalDatabaseTenant("library-a");
        TenantContext.beginMembershipDiagnostics(true);
        TenantContext.clear();

        assertThat(TenantContext.membershipDiagnosticSummary(false))
                .isEqualTo("provisional_scope_present=false transaction_active=false "
                        + "tenant_arming_attempted=false tenant_arming_succeeded=false membership_found=false");
    }
}
