package com.libranet.catalog.service;

import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.billing.service.EntitlementService;
import com.libranet.catalog.dto.CategoryDtos.CategoryNodeDto;
import com.libranet.catalog.dto.CategoryDtos.UpsertCategoryRequest;
import com.libranet.catalog.dto.LocationDtos.BranchDto;
import com.libranet.catalog.dto.LocationDtos.ShelfLocationDto;
import com.libranet.catalog.dto.LocationDtos.UpsertBranchRequest;
import com.libranet.catalog.dto.LocationDtos.UpsertShelfLocationRequest;
import com.libranet.catalog.model.Branch;
import com.libranet.catalog.model.Category;
import com.libranet.catalog.model.ShelfLocation;
import com.libranet.catalog.repo.BookCopyRepository;
import com.libranet.catalog.repo.BookRepository;
import com.libranet.catalog.repo.BranchRepository;
import com.libranet.catalog.repo.CategoryRepository;
import com.libranet.catalog.repo.ShelfLocationRepository;
import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.TenantContext;
import com.libranet.common.tenant.TenantGuard;
import com.libranet.membership.model.LibraryRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.libranet.permissions.model.LibraryPerm;
import com.libranet.permissions.service.PermissionService;

/** Categories (tree), branches and shelf locations. Reads: any member; writes: staff. */
@Service
public class TaxonomyService {

    private final CategoryRepository categories;
    private final BranchRepository branches;
    private final ShelfLocationRepository shelfLocations;
    private final BookRepository books;
    private final BookCopyRepository copies;
    private final EntitlementService entitlements;
    private final AuditService audit;

        private final PermissionService permissions;

    public TaxonomyService(CategoryRepository categories, BranchRepository branches,
                           ShelfLocationRepository shelfLocations, BookRepository books,
                           BookCopyRepository copies, EntitlementService entitlements, AuditService audit,
                            PermissionService permissions) {
        this.permissions = permissions;
        this.categories = categories;
        this.branches = branches;
        this.shelfLocations = shelfLocations;
        this.books = books;
        this.copies = copies;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    // ---------- categories ----------

    @Transactional(readOnly = true)
    public List<CategoryNodeDto> categoryTree() {
        String tenantId = TenantContext.requireLibraryId();
        List<Category> all = categories.findByTenantIdOrderBySortOrderAscNameAsc(tenantId);
        Map<String, List<Category>> byParent = all.stream().collect(Collectors.groupingBy(
                c -> c.getParentId() == null ? "" : c.getParentId()));
        return buildTree(byParent.getOrDefault("", List.of()), byParent, tenantId);
    }

    private List<CategoryNodeDto> buildTree(List<Category> nodes, Map<String, List<Category>> byParent,
                                            String tenantId) {
        List<CategoryNodeDto> result = new ArrayList<>();
        for (Category c : nodes) {
            result.add(CategoryNodeDto.of(c, books.countByTenantIdAndCategoryId(tenantId, c.getId()),
                    buildTree(byParent.getOrDefault(c.getId(), List.of()), byParent, tenantId)));
        }
        return result;
    }

    @Transactional
    public CategoryNodeDto createCategory(UpsertCategoryRequest req) {
        String tenantId = permissions.require(LibraryPerm.CATALOG_MANAGE).libraryId();
        Category category = new Category();
        category.setTenantId(tenantId);
        applyCategory(category, req, null);
        try {
            categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("CATEGORY_NAME_TAKEN", "A category with this name already exists here");
        }
        audit.log(AuditAction.CATEGORY_CREATED, "Category", category.getId(), Map.of("name", category.getName()));
        return CategoryNodeDto.of(category, 0, List.of());
    }

    @Transactional
    public CategoryNodeDto updateCategory(String categoryId, UpsertCategoryRequest req) {
        permissions.require(LibraryPerm.CATALOG_MANAGE);
        Category category = findScopedCategory(categoryId);
        applyCategory(category, req, categoryId);
        try {
            categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("CATEGORY_NAME_TAKEN", "A category with this name already exists here");
        }
        audit.log(AuditAction.CATEGORY_UPDATED, "Category", categoryId, Map.of("name", category.getName()));
        return CategoryNodeDto.of(category,
                books.countByTenantIdAndCategoryId(category.getTenantId(), categoryId), List.of());
    }

    @Transactional
    public void deleteCategory(String categoryId) {
        String tenantId = permissions.require(LibraryPerm.CATALOG_MANAGE).libraryId();
        Category category = findScopedCategory(categoryId);
        if (categories.existsByTenantIdAndParentId(tenantId, categoryId)) {
            throw ApiException.conflict("CATEGORY_HAS_CHILDREN", "Move or delete the sub-categories first");
        }
        long inUse = books.countByTenantIdAndCategoryId(tenantId, categoryId);
        if (inUse > 0) {
            throw ApiException.conflict("CATEGORY_IN_USE",
                    "This category is used by " + inUse + " book(s); reassign them first");
        }
        categories.delete(category);
        audit.log(AuditAction.CATEGORY_DELETED, "Category", categoryId, Map.of("name", category.getName()));
    }

    private void applyCategory(Category category, UpsertCategoryRequest req, String selfId) {
        String parentId = req.parentId() == null || req.parentId().isBlank() ? null : req.parentId().trim();
        if (parentId != null) {
            if (parentId.equals(selfId)) {
                throw ApiException.badRequest("CATEGORY_CYCLE", "A category cannot be its own parent");
            }
            Category parent = categories.findById(parentId)
                    .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Parent category not found"));
            TenantGuard.requireBelongsToCurrentTenant(parent.getTenantId());
        }
        category.setParentId(parentId);
        category.setName(req.name().trim());
        category.setKind(req.kind() == null || req.kind().isBlank() ? null : req.kind().trim());
        category.setDescription(req.description() == null || req.description().isBlank()
                ? null : req.description().trim());
        category.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
    }

    private Category findScopedCategory(String categoryId) {
        Category category = categories.findById(categoryId)
                .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found"));
        TenantGuard.requireBelongsToCurrentTenant(category.getTenantId());
        return category;
    }

    // ---------- branches ----------

    @Transactional(readOnly = true)
    public List<BranchDto> listBranches() {
        return branches.findByTenantIdOrderByMainDescNameAsc(TenantContext.requireLibraryId())
                .stream().map(BranchDto::from).toList();
    }

    @Transactional
    public BranchDto createBranch(UpsertBranchRequest req) {
        String tenantId = permissions.require(LibraryPerm.CATALOG_MANAGE).libraryId();
        entitlements.requireBranchCapacity(tenantId, () -> branches.countByTenantId(tenantId));
        Branch branch = new Branch();
        branch.setTenantId(tenantId);
        applyBranch(branch, req);
        try {
            branches.saveAndFlush(branch);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("BRANCH_NAME_TAKEN", "A branch with this name already exists");
        }
        audit.log(AuditAction.BRANCH_CREATED, "Branch", branch.getId(), Map.of("name", branch.getName()));
        return BranchDto.from(branch);
    }

    @Transactional
    public BranchDto updateBranch(String branchId, UpsertBranchRequest req) {
        permissions.require(LibraryPerm.CATALOG_MANAGE);
        Branch branch = findScopedBranch(branchId);
        applyBranch(branch, req);
        try {
            branches.saveAndFlush(branch);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("BRANCH_NAME_TAKEN", "A branch with this name already exists");
        }
        audit.log(AuditAction.BRANCH_UPDATED, "Branch", branchId, Map.of("name", branch.getName()));
        return BranchDto.from(branch);
    }

    @Transactional
    public void deleteBranch(String branchId) {
        String tenantId = permissions.require(LibraryPerm.CATALOG_MANAGE).libraryId();
        Branch branch = findScopedBranch(branchId);
        if (copies.countByTenantIdAndBranchId(tenantId, branchId) > 0) {
            throw ApiException.conflict("BRANCH_HAS_COPIES", "Reassign or remove this branch's copies first");
        }
        if (shelfLocations.countByTenantIdAndBranchId(tenantId, branchId) > 0) {
            throw ApiException.conflict("BRANCH_HAS_LOCATIONS", "Remove this branch's shelf locations first");
        }
        branches.delete(branch);
        audit.log(AuditAction.BRANCH_DELETED, "Branch", branchId, Map.of("name", branch.getName()));
    }

    private void applyBranch(Branch branch, UpsertBranchRequest req) {
        branch.setName(req.name().trim());
        branch.setCode(req.code() == null || req.code().isBlank() ? null : req.code().trim().toUpperCase());
        branch.setAddress(req.address() == null || req.address().isBlank() ? null : req.address().trim());
        branch.setPhone(req.phone() == null || req.phone().isBlank() ? null : req.phone().trim());
        branch.setEmail(req.email() == null || req.email().isBlank() ? null : req.email().trim());
        if (req.main() != null) {
            branch.setMain(req.main());
        }
    }

    private Branch findScopedBranch(String branchId) {
        Branch branch = branches.findById(branchId)
                .orElseThrow(() -> ApiException.notFound("BRANCH_NOT_FOUND", "Branch not found"));
        TenantGuard.requireBelongsToCurrentTenant(branch.getTenantId());
        return branch;
    }

    // ---------- shelf locations ----------

    @Transactional(readOnly = true)
    public List<ShelfLocationDto> listShelfLocations(String branchId) {
        String tenantId = TenantContext.requireLibraryId();
        Map<String, String> branchNames = branches.findByTenantIdOrderByMainDescNameAsc(tenantId)
                .stream().collect(Collectors.toMap(Branch::getId, Branch::getName));
        List<ShelfLocation> locations = branchId == null || branchId.isBlank()
                ? shelfLocations.findByTenantIdOrderByBranchIdAscBuildingAscFloorAscShelfAsc(tenantId)
                : shelfLocations.findByTenantIdAndBranchIdOrderByFloorAscShelfAsc(tenantId, branchId.trim());
        return locations.stream()
                .map(s -> ShelfLocationDto.of(s, branchNames.get(s.getBranchId()))).toList();
    }

    @Transactional
    public ShelfLocationDto createShelfLocation(UpsertShelfLocationRequest req) {
        String tenantId = permissions.require(LibraryPerm.CATALOG_MANAGE).libraryId();
        Branch branch = findScopedBranch(req.branchId().trim());
        ShelfLocation location = new ShelfLocation();
        location.setTenantId(tenantId);
        applyShelf(location, req);
        location.setBranchId(branch.getId());
        try {
            shelfLocations.saveAndFlush(location);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("LOCATION_EXISTS", "This exact shelf location already exists");
        }
        audit.log(AuditAction.SHELF_LOCATION_CREATED, "ShelfLocation", location.getId(),
                Map.of("display", location.display()));
        return ShelfLocationDto.of(location, branch.getName());
    }

    @Transactional
    public ShelfLocationDto updateShelfLocation(String locationId, UpsertShelfLocationRequest req) {
        permissions.require(LibraryPerm.CATALOG_MANAGE);
        ShelfLocation location = findScopedShelf(locationId);
        Branch branch = findScopedBranch(req.branchId().trim());
        applyShelf(location, req);
        location.setBranchId(branch.getId());
        try {
            shelfLocations.saveAndFlush(location);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("LOCATION_EXISTS", "This exact shelf location already exists");
        }
        audit.log(AuditAction.SHELF_LOCATION_UPDATED, "ShelfLocation", locationId,
                Map.of("display", location.display()));
        return ShelfLocationDto.of(location, branch.getName());
    }

    @Transactional
    public void deleteShelfLocation(String locationId) {
        permissions.require(LibraryPerm.CATALOG_MANAGE);
        ShelfLocation location = findScopedShelf(locationId);
        if (copies.countByTenantIdAndShelfLocationId(location.getTenantId(), locationId) > 0) {
            throw ApiException.conflict("LOCATION_HAS_COPIES", "Reassign or remove this location's copies first");
        }
        shelfLocations.delete(location);
        audit.log(AuditAction.SHELF_LOCATION_DELETED, "ShelfLocation", locationId,
                Map.of("display", location.display()));
    }

    private void applyShelf(ShelfLocation location, UpsertShelfLocationRequest req) {
        location.setBuilding(req.building() == null || req.building().isBlank() ? "Main" : req.building().trim());
        location.setFloor(req.floor().trim());
        location.setSection(req.section().trim());
        location.setShelf(req.shelf().trim());
        location.setRowLabel(req.rowLabel() == null || req.rowLabel().isBlank() ? null : req.rowLabel().trim());
    }

    private ShelfLocation findScopedShelf(String locationId) {
        ShelfLocation location = shelfLocations.findById(locationId)
                .orElseThrow(() -> ApiException.notFound("SHELF_LOCATION_NOT_FOUND", "Shelf location not found"));
        TenantGuard.requireBelongsToCurrentTenant(location.getTenantId());
        return location;
    }
}
