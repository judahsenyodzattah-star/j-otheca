package com.jotheca.membership.repo;

import com.jotheca.membership.model.Membership;
import com.jotheca.membership.model.MembershipStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Membership> {

    Optional<Membership> findByTenantIdAndUserId(String tenantId, String userId);

    Optional<Membership> findByTenantIdAndMembershipNumber(String tenantId, String membershipNumber);

    Optional<Membership> findByTenantIdAndUserIdAndStatus(String tenantId, String userId, MembershipStatus status);

    Page<Membership> findByTenantId(String tenantId, Pageable pageable);

    Page<Membership> findByTenantIdAndStatus(String tenantId, MembershipStatus status, Pageable pageable);

    List<Membership> findByUserId(String userId);

    List<Membership> findByTenantIdAndRoleInAndStatus(String tenantId, java.util.Collection<com.jotheca.membership.model.LibraryRole> roles,
                                                      com.jotheca.membership.model.MembershipStatus status);

    long countByTenantIdAndStatus(String tenantId, MembershipStatus status);
    /** Platform analytics: memberships grouped by status cross-tenant. */
    long countByStatus(MembershipStatus status);

}
