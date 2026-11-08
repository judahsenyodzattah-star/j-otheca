package com.jotheca.tenancy.service;

import com.jotheca.common.tenant.TenantContext;
import com.jotheca.membership.model.Membership;
import com.jotheca.membership.model.MembershipStatus;
import com.jotheca.membership.repo.MembershipRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Performs membership authorization lookups inside a real Spring-managed transaction. */
@Service
public class TenantMembershipResolutionService {

    private final MembershipRepository memberships;

    public TenantMembershipResolutionService(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public Optional<Membership> findApprovedMembership(String tenantId, String userId) {
        TenantContext.markMembershipTransactionActive();
        return memberships.findByTenantIdAndUserIdAndStatus(tenantId, userId, MembershipStatus.APPROVED);
    }
}
