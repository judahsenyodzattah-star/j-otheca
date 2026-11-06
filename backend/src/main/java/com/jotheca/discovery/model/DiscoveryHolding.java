package com.jotheca.discovery.model;

import com.jotheca.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** Internal per-library holding projection. Private source IDs are intentionally absent. */
@Entity
@Table(name = "holdings", schema = "discovery")
public class DiscoveryHolding extends BaseEntity {

    @Column(name = "edition_id", nullable = false, length = 36)
    private String editionId;
    @Column(name = "library_public_id", nullable = false, length = 36)
    private String libraryPublicId;
    @Column(name = "library_slug", nullable = false, length = 120)
    private String librarySlug;
    @Column(name = "library_name", nullable = false, length = 200)
    private String libraryName;
    @Column(name = "library_logo_ref", length = 500)
    private String libraryLogoRef;
    @Column(length = 100)
    private String country;
    @Column(length = 100)
    private String city;
    @Column(name = "catalog_visibility", nullable = false, length = 20)
    private String catalogVisibility;
    @Column(name = "holdings_visibility", nullable = false, length = 20)
    private String holdingsVisibility;
    @Column(name = "availability_status", length = 20)
    private String availabilityStatus;
    @Column(name = "copy_count")
    private Integer copyCount;
    @Column(name = "branch_summary", columnDefinition = "TEXT")
    private String branchSummary;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "projection_version", nullable = false)
    private long projectionVersion;

    public String getEditionId() { return editionId; }
    public void setEditionId(String value) { editionId = value; }
    public String getLibraryPublicId() { return libraryPublicId; }
    public void setLibraryPublicId(String value) { libraryPublicId = value; }
    public String getLibrarySlug() { return librarySlug; }
    public void setLibrarySlug(String value) { librarySlug = value; }
    public String getLibraryName() { return libraryName; }
    public void setLibraryName(String value) { libraryName = value; }
    public String getLibraryLogoRef() { return libraryLogoRef; }
    public void setLibraryLogoRef(String value) { libraryLogoRef = value; }
    public String getCountry() { return country; }
    public void setCountry(String value) { country = value; }
    public String getCity() { return city; }
    public void setCity(String value) { city = value; }
    public String getCatalogVisibility() { return catalogVisibility; }
    public void setCatalogVisibility(String value) { catalogVisibility = value; }
    public String getHoldingsVisibility() { return holdingsVisibility; }
    public void setHoldingsVisibility(String value) { holdingsVisibility = value; }
    public String getAvailabilityStatus() { return availabilityStatus; }
    public void setAvailabilityStatus(String value) { availabilityStatus = value; }
    public Integer getCopyCount() { return copyCount; }
    public void setCopyCount(Integer value) { copyCount = value; }
    public String getBranchSummary() { return branchSummary; }
    public void setBranchSummary(String value) { branchSummary = value; }
    public boolean isActive() { return active; }
    public void setActive(boolean value) { active = value; }
    public long getProjectionVersion() { return projectionVersion; }
    public void setProjectionVersion(long value) { projectionVersion = value; }
}
