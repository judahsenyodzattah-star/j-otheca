package com.jotheca.catalog.repo;

import com.jotheca.catalog.model.Branch;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchRepository extends JpaRepository<Branch, String> {

    List<Branch> findByTenantIdOrderByMainDescNameAsc(String tenantId);

    Optional<Branch> findFirstByTenantIdAndMainTrue(String tenantId);

    Optional<Branch> findByTenantIdAndNameIgnoreCase(String tenantId, String name);

    long countByTenantId(String tenantId);
}
