package com.jotheca.catalog.model;

import com.jotheca.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Physical address of a copy inside a branch (spec §18):
 * Branch → Building → Floor → Section → Shelf → Row.
 * Display example: "Shelf A-12, Floor 2, Computer Science".
 */
@Entity
@Table(name = "shelf_locations")
public class ShelfLocation extends TenantScopedEntity {

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(nullable = false, length = 60)
    private String building = "Main";

    @Column(nullable = false, length = 60)
    private String floor;

    @Column(nullable = false, length = 100)
    private String section;

    @Column(nullable = false, length = 60)
    private String shelf;

    @Column(name = "row_label", length = 60)
    private String rowLabel;

    /** Friendly single-line address shown on book pages and member app. */
    public String display() {
        StringBuilder sb = new StringBuilder("Shelf ").append(shelf);
        if (rowLabel != null && !rowLabel.isBlank()) {
            sb.append(", Row ").append(rowLabel);
        }
        sb.append(", Floor ").append(floor).append(", ").append(section);
        if (building != null && !building.isBlank() && !"Main".equalsIgnoreCase(building)) {
            sb.append(" (").append(building).append(')');
        }
        return sb.toString();
    }

    public String getBranchId() { return branchId; }
    public void setBranchId(String branchId) { this.branchId = branchId; }
    public String getBuilding() { return building; }
    public void setBuilding(String building) { this.building = building; }
    public String getFloor() { return floor; }
    public void setFloor(String floor) { this.floor = floor; }
    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }
    public String getShelf() { return shelf; }
    public void setShelf(String shelf) { this.shelf = shelf; }
    public String getRowLabel() { return rowLabel; }
    public void setRowLabel(String rowLabel) { this.rowLabel = rowLabel; }
}
