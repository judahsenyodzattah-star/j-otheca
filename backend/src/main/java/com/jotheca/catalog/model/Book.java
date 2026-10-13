package com.jotheca.catalog.model;

import com.jotheca.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A bibliographic title owned by a library (spec §17). Physical units are {@link BookCopy}s.
 * ISBN uniqueness is per-tenant — two libraries may (and will) hold the same work.
 */
@Entity
@Table(name = "books")
public class Book extends TenantScopedEntity {

    /** Tenant-unique URL key for the public book page (Phase 6, e.g. /book/things-fall-apart-x7k2). */
    @Column(nullable = false, length = 160)
    private String slug;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 500)
    private String subtitle;

    @Column(nullable = false, length = 300)
    private String author;

    @Column(name = "co_authors", length = 500)
    private String coAuthors;

    /** Normalized: hyphens/spaces stripped, upper-cased. Unique per tenant when present. */
    @Column(length = 20)
    private String isbn;

    @Column(length = 200)
    private String publisher;

    /** ISO yyyy or yyyy-MM-dd; legacy catalogs often carry the year only. */
    @Column(name = "published_date", length = 10)
    private String publishedDate;

    @Column(length = 60)
    private String edition;

    @Column(length = 60)
    private String language;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Comma-separated search keywords. */
    @Column(length = 500)
    private String subjects;

    @Column(name = "category_id", length = 36)
    private String categoryId;

    @Column(name = "cover_ref", length = 500)
    private String coverRef;

    /** Rating aggregates, maintained by the reviews module (Phase 7). */
    @Column(name = "rating_avg", nullable = false)
    private double ratingAvg;

    @Column(name = "rating_count", nullable = false)
    private int ratingCount;

    /** Staff can hide a title from patrons without deleting it (curation, damage review...). */
    @Column(nullable = false)
    private boolean published = true;

    /** The platform user who authored this title (author/personal libraries, spec §24); null for 3rd-party works. */
    @Column(name = "author_user_id", length = 36)
    private String authorUserId;

    /**
     * E18 (audit P3): link to the author's public capability profile — the identity behind
     * the public author page and studio aggregates. {@code authorUserId} remains the legacy
     * personal-library pointer; both stay optional.
     */
    @Column(name = "author_profile_id", length = 36)
    private String authorProfileId;

    /** Physical-sale sticker price in minor units; NULL = this title is not for sale here. */
    @Column(name = "sale_price_minor")
    private Long salePriceMinor;

    /** Short staff note shown on the public page, e.g. "Signed copies at the front desk". */
    @Column(name = "contact_note", length = 300)
    private String contactNote;

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String getCoAuthors() { return coAuthors; }
    public void setCoAuthors(String coAuthors) { this.coAuthors = coAuthors; }
    public String getIsbn() { return isbn; }
    public void setIsbn(String isbn) { this.isbn = isbn; }
    public String getPublisher() { return publisher; }
    public void setPublisher(String publisher) { this.publisher = publisher; }
    public String getPublishedDate() { return publishedDate; }
    public void setPublishedDate(String publishedDate) { this.publishedDate = publishedDate; }
    public String getEdition() { return edition; }
    public void setEdition(String edition) { this.edition = edition; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSubjects() { return subjects; }
    public void setSubjects(String subjects) { this.subjects = subjects; }
    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }
    public String getCoverRef() { return coverRef; }
    public void setCoverRef(String coverRef) { this.coverRef = coverRef; }
    public double getRatingAvg() { return ratingAvg; }
    public void setRatingAvg(double ratingAvg) { this.ratingAvg = ratingAvg; }
    public int getRatingCount() { return ratingCount; }
    public void setRatingCount(int ratingCount) { this.ratingCount = ratingCount; }
    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }
    public String getAuthorUserId() { return authorUserId; }
    public void setAuthorUserId(String authorUserId) { this.authorUserId = authorUserId; }
    public String getAuthorProfileId() { return authorProfileId; }
    public void setAuthorProfileId(String authorProfileId) { this.authorProfileId = authorProfileId; }
    public Long getSalePriceMinor() { return salePriceMinor; }
    public void setSalePriceMinor(Long salePriceMinor) { this.salePriceMinor = salePriceMinor; }
    public String getContactNote() { return contactNote; }
    public void setContactNote(String contactNote) { this.contactNote = contactNote; }
}
