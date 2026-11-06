package com.jotheca.discovery.model;

import com.jotheca.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** Internal sanitized edition projection. Not a public API DTO. */
@Entity
@Table(name = "editions", schema = "discovery")
public class DiscoveryEdition extends BaseEntity {

    @Column(name = "edition_key", nullable = false, length = 220, unique = true)
    private String editionKey;
    @Column(name = "normalized_isbn13", length = 13)
    private String normalizedIsbn13;
    @Column(nullable = false, length = 500)
    private String title;
    @Column(length = 500)
    private String subtitle;
    @Column(name = "author_display", nullable = false, length = 500)
    private String authorDisplay;
    @Column(length = 200)
    private String publisher;
    @Column(name = "published_date", length = 10)
    private String publishedDate;
    @Column(name = "edition_label", length = 60)
    private String editionLabel;
    @Column(length = 60)
    private String language;
    @Column(name = "page_count")
    private Integer pageCount;
    @Column(name = "cover_ref", length = 500)
    private String coverRef;
    @Column(length = 500)
    private String subjects;
    @Column(name = "metadata_fingerprint", nullable = false, length = 64)
    private String metadataFingerprint;
    @Enumerated(EnumType.STRING)
    @Column(name = "metadata_state", nullable = false, length = 20)
    private DiscoveryMetadataState metadataState = DiscoveryMetadataState.CONSISTENT;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "projection_version", nullable = false)
    private long projectionVersion;

    public String getEditionKey() { return editionKey; }
    public void setEditionKey(String value) { editionKey = value; }
    public String getNormalizedIsbn13() { return normalizedIsbn13; }
    public void setNormalizedIsbn13(String value) { normalizedIsbn13 = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String value) { subtitle = value; }
    public String getAuthorDisplay() { return authorDisplay; }
    public void setAuthorDisplay(String value) { authorDisplay = value; }
    public String getPublisher() { return publisher; }
    public void setPublisher(String value) { publisher = value; }
    public String getPublishedDate() { return publishedDate; }
    public void setPublishedDate(String value) { publishedDate = value; }
    public String getEditionLabel() { return editionLabel; }
    public void setEditionLabel(String value) { editionLabel = value; }
    public String getLanguage() { return language; }
    public void setLanguage(String value) { language = value; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer value) { pageCount = value; }
    public String getCoverRef() { return coverRef; }
    public void setCoverRef(String value) { coverRef = value; }
    public String getSubjects() { return subjects; }
    public void setSubjects(String value) { subjects = value; }
    public String getMetadataFingerprint() { return metadataFingerprint; }
    public void setMetadataFingerprint(String value) { metadataFingerprint = value; }
    public DiscoveryMetadataState getMetadataState() { return metadataState; }
    public void setMetadataState(DiscoveryMetadataState value) { metadataState = value; }
    public boolean isActive() { return active; }
    public void setActive(boolean value) { active = value; }
    public long getProjectionVersion() { return projectionVersion; }
    public void setProjectionVersion(long value) { projectionVersion = value; }
}
