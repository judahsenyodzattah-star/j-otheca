package com.libranet.authors.model;

import com.libranet.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Author capability attached to a User (audit P3 — spec: author is a capability, not a
 * separate account; it coexists with patron/staff memberships). NOT tenant-scoped: an
 * author's identity spans libraries. Privacy-first: {@code publicProfile} gates the public
 * page, and the legal full name on the User row is never published from here. Payout
 * fields are written ONLY by the seller-activation endpoint (implementation rule #15).
 */
@Entity
@Table(name = "author_profiles")
public class AuthorProfile extends BaseEntity {

    public enum AuthorType { INDIE, TRADITIONALLY_PUBLISHED, HYBRID, ORGANIZATION }

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, length = 80)
    private String slug;

    /** The name readers see. </b>Never<b> the legal name unless the author types it here themselves. */
    @Column(name = "pen_name", nullable = false, length = 120)
    private String penName;

    @Column(length = 2000)
    private String bio;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    @Column(length = 100)
    private String country;

    /** CSV — rendered as chips. */
    @Column(length = 300)
    private String languages;

    @Column(length = 300)
    private String genres;

    /** JSON array of {label,url} — validated shape in the service, never markup-rendered raw. */
    @Column(columnDefinition = "TEXT")
    private String links;

    @Column(name = "author_type", nullable = false, length = 24)
    private String authorType = AuthorType.INDIE.name();

    @Column(name = "public_profile", nullable = false)
    private boolean publicProfile;

    @Column(name = "public_email", length = 255)
    private String publicEmail;

    @Column(name = "selling_enabled", nullable = false)
    private boolean sellingEnabled;

    @Column(name = "payout_momo_number", length = 32)
    private String payoutMomoNumber;

    @Column(name = "payout_momo_network", length = 32)
    private String payoutMomoNetwork;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getPenName() { return penName; }
    public void setPenName(String penName) { this.penName = penName; }
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public String getLanguages() { return languages; }
    public void setLanguages(String languages) { this.languages = languages; }
    public String getGenres() { return genres; }
    public void setGenres(String genres) { this.genres = genres; }
    public String getLinks() { return links; }
    public void setLinks(String links) { this.links = links; }
    public String getAuthorType() { return authorType; }
    public void setAuthorType(String authorType) { this.authorType = authorType; }
    public boolean isPublicProfile() { return publicProfile; }
    public void setPublicProfile(boolean publicProfile) { this.publicProfile = publicProfile; }
    public String getPublicEmail() { return publicEmail; }
    public void setPublicEmail(String publicEmail) { this.publicEmail = publicEmail; }
    public boolean isSellingEnabled() { return sellingEnabled; }
    public void setSellingEnabled(boolean sellingEnabled) { this.sellingEnabled = sellingEnabled; }
    public String getPayoutMomoNumber() { return payoutMomoNumber; }
    public void setPayoutMomoNumber(String payoutMomoNumber) { this.payoutMomoNumber = payoutMomoNumber; }
    public String getPayoutMomoNetwork() { return payoutMomoNetwork; }
    public void setPayoutMomoNetwork(String payoutMomoNetwork) { this.payoutMomoNetwork = payoutMomoNetwork; }
}
