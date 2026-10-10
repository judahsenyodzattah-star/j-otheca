package com.jotheca.catalog.dto;

import com.jotheca.catalog.model.Category;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class CategoryDtos {

    private CategoryDtos() {}

    /** Category tree node (children nested). */
    public record CategoryNodeDto(String id, String parentId, String name, String kind,
                                  String description, int sortOrder, long bookCount,
                                  List<CategoryNodeDto> children) {
        public static CategoryNodeDto of(Category c, long bookCount, List<CategoryNodeDto> children) {
            return new CategoryNodeDto(c.getId(), c.getParentId(), c.getName(), c.getKind(),
                    c.getDescription(), c.getSortOrder(), bookCount, children);
        }
    }

    public record UpsertCategoryRequest(
            @Size(max = 36) String parentId,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 40) String kind,
            @Size(max = 1000) String description,
            Integer sortOrder) {}
}
