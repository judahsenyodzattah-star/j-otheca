package com.libranet.circulation.repo;

import com.libranet.circulation.model.Fine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FineRepository extends JpaRepository<Fine, String> {

    List<Fine> findByTenantIdAndMembershipIdOrderByCreatedAtDesc(String tenantId, String membershipId);

    List<Fine> findByTenantIdAndMembershipIdAndStatus(String tenantId, String membershipId, Fine.Status status);

    List<Fine> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    long countByTenantIdAndMembershipIdAndStatus(String tenantId, String membershipId, Fine.Status status);
    /** Dashboards: fines grouped by status for one tenant. */
    long countByTenantIdAndStatus(String tenantId, Fine.Status status);

    /** Dashboards: monetary totals (OPEN only) per tenant / per member, minor units. */
    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(f.amountMinor), 0) from Fine f where f.tenantId = :tenantId and f.status = com.libranet.circulation.model.Fine.Status.OPEN")
    long openBalanceForTenant(@org.springframework.data.repository.query.Param("tenantId") String tenantId);

    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(f.amountMinor), 0) from Fine f where f.tenantId = :tenantId and f.membershipId = :membershipId and f.status = com.libranet.circulation.model.Fine.Status.OPEN")
    long openBalanceForMember(@org.springframework.data.repository.query.Param("tenantId") String tenantId,
                              @org.springframework.data.repository.query.Param("membershipId") String membershipId);

}
