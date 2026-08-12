package com.libranet.catalog.model;

import com.libranet.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Per-tenant category tree (spec §17). {@code parentId == null} marks a root. */
@Entity
@Table(name = "categories")
public class Category extends TenantScopedEntity {

    @Column(name = "parent_id", length = 36)
    private String parentId;

    @Column(nullable = false, length = 200)
    private String name;

    /** Shelving-area label shown in location strings, e.g. "Computer Science" (spec §18). */
    @Column(length = 40)
    private String kind;

    @Column(length = 1000)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
