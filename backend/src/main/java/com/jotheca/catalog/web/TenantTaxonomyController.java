package com.jotheca.catalog.web;

import com.jotheca.catalog.dto.CategoryDtos.CategoryNodeDto;
import com.jotheca.catalog.dto.CategoryDtos.UpsertCategoryRequest;
import com.jotheca.catalog.dto.LocationDtos.BranchDto;
import com.jotheca.catalog.dto.LocationDtos.ShelfLocationDto;
import com.jotheca.catalog.dto.LocationDtos.UpsertBranchRequest;
import com.jotheca.catalog.dto.LocationDtos.UpsertShelfLocationRequest;
import com.jotheca.catalog.service.TaxonomyService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Categories (tree), branches and shelf locations — the library's physical & topical structure. */
@RestController
@RequestMapping("/api/v1/tenant/catalog")
public class TenantTaxonomyController {

    private final TaxonomyService taxonomy;

    public TenantTaxonomyController(TaxonomyService taxonomy) {
        this.taxonomy = taxonomy;
    }

    @GetMapping("/categories")
    public List<CategoryNodeDto> categories() {
        return taxonomy.categoryTree();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryNodeDto createCategory(@Valid @RequestBody UpsertCategoryRequest req) {
        return taxonomy.createCategory(req);
    }

    @PutMapping("/categories/{id}")
    public CategoryNodeDto updateCategory(@PathVariable String id, @Valid @RequestBody UpsertCategoryRequest req) {
        return taxonomy.updateCategory(id, req);
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable String id) {
        taxonomy.deleteCategory(id);
    }

    @GetMapping("/branches")
    public List<BranchDto> branches() {
        return taxonomy.listBranches();
    }

    @PostMapping("/branches")
    @ResponseStatus(HttpStatus.CREATED)
    public BranchDto createBranch(@Valid @RequestBody UpsertBranchRequest req) {
        return taxonomy.createBranch(req);
    }

    @PutMapping("/branches/{id}")
    public BranchDto updateBranch(@PathVariable String id, @Valid @RequestBody UpsertBranchRequest req) {
        return taxonomy.updateBranch(id, req);
    }

    @DeleteMapping("/branches/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBranch(@PathVariable String id) {
        taxonomy.deleteBranch(id);
    }

    @GetMapping("/shelf-locations")
    public List<ShelfLocationDto> shelfLocations(@RequestParam(required = false) String branchId) {
        return taxonomy.listShelfLocations(branchId);
    }

    @PostMapping("/shelf-locations")
    @ResponseStatus(HttpStatus.CREATED)
    public ShelfLocationDto createShelfLocation(@Valid @RequestBody UpsertShelfLocationRequest req) {
        return taxonomy.createShelfLocation(req);
    }

    @PutMapping("/shelf-locations/{id}")
    public ShelfLocationDto updateShelfLocation(@PathVariable String id,
                                                @Valid @RequestBody UpsertShelfLocationRequest req) {
        return taxonomy.updateShelfLocation(id, req);
    }

    @DeleteMapping("/shelf-locations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteShelfLocation(@PathVariable String id) {
        taxonomy.deleteShelfLocation(id);
    }
}
