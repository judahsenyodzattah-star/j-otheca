package com.libranet.catalog.repo;

import com.libranet.catalog.model.Category;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, String> {

    List<Category> findByTenantIdOrderBySortOrderAscNameAsc(String tenantId);

    Optional<Category> findByTenantIdAndNameIgnoreCase(String tenantId, String name);

    Optional<Category> findFirstByTenantIdAndParentIdIsNullAndNameIgnoreCase(String tenantId, String name);

    boolean existsByTenantIdAndParentId(String tenantId, String parentId);

    long countByTenantId(String tenantId);
}
