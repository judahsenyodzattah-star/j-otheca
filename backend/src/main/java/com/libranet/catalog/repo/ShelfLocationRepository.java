package com.libranet.catalog.repo;

import com.libranet.catalog.model.ShelfLocation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShelfLocationRepository extends JpaRepository<ShelfLocation, String> {

    List<ShelfLocation> findByTenantIdOrderByBranchIdAscBuildingAscFloorAscShelfAsc(String tenantId);

    List<ShelfLocation> findByTenantIdAndBranchIdOrderByFloorAscShelfAsc(String tenantId, String branchId);

    long countByTenantIdAndBranchId(String tenantId, String branchId);
}
