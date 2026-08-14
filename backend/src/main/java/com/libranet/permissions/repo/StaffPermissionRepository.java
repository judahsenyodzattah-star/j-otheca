package com.libranet.permissions.repo;

import com.libranet.permissions.model.StaffPermission;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffPermissionRepository extends JpaRepository<StaffPermission, String> {

    List<StaffPermission> findByTenantIdAndMembershipId(String tenantId, String membershipId);

    Optional<StaffPermission> findByTenantIdAndMembershipIdAndPerm(String tenantId, String membershipId,
                                                                   String perm);
}
