package com.jotheca.tenancy.model;

import com.jotheca.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A tenant root. Every tenant-owned row in the system references this id as tenant_id.
 * {@code uid} (LIB-XXXXXX) is the internal permanent identifier; {@code slug} is the public one.
 */
@Entity
@Table(name = "libraries")
public class Library extends BaseEntity {

    @Column(nullable = false, length = 12)
    private String uid;

    @Column(nullable = false, length = 120)
    private String slug;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LibraryType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LibraryStatus status = LibraryStatus.PENDING_APPROVAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "join_policy", nullable = false, length = 30)
    private JoinPolicy joinPolicy = JoinPolicy.PUBLIC;

    @Column(nullable = false)
    private boolean visibility = true;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 100)
    private String country;

    @Column(length = 100)
    private String city;

    @Column(length = 300)
    private String address;

    private Double latitude;
    private Double longitude;

    @Column(name = "opening_hours", length = 300)
    private String openingHours;

    @Column(length = 500)
    private String services;

    @Column(name = "allowed_domain", length = 200)
    private String allowedDomain;

    @Column(name = "logo_ref", length = 500)
    private String logoRef;

    @Column(name = "banner_ref", length = 500)
    private String bannerRef;

    @Column(name = "member_count", nullable = false)
    private int memberCount;

    /** Review policy (Phase 7): when false, review requests carrying anonymous=true are rejected. */
    @Column(name = "allow_anonymous_reviews", nullable = false)
    private boolean allowAnonymousReviews;

    public String getUid() { return uid; }
    public void setUid(String uid) { this.uid = uid; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LibraryType getType() { return type; }
    public void setType(LibraryType type) { this.type = type; }
    public LibraryStatus getStatus() { return status; }
    public void setStatus(LibraryStatus status) { this.status = status; }
    public JoinPolicy getJoinPolicy() { return joinPolicy; }
    public void setJoinPolicy(JoinPolicy joinPolicy) { this.joinPolicy = joinPolicy; }
    public boolean isVisibility() { return visibility; }
    public void setVisibility(boolean visibility) { this.visibility = visibility; }
    public boolean isAllowAnonymousReviews() { return allowAnonymousReviews; }
    public void setAllowAnonymousReviews(boolean allowAnonymousReviews) {
        this.allowAnonymousReviews = allowAnonymousReviews;
    }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public String getOpeningHours() { return openingHours; }
    public void setOpeningHours(String openingHours) { this.openingHours = openingHours; }
    public String getServices() { return services; }
    public void setServices(String services) { this.services = services; }
    public String getAllowedDomain() { return allowedDomain; }
    public void setAllowedDomain(String allowedDomain) { this.allowedDomain = allowedDomain; }
    public String getLogoRef() { return logoRef; }
    public void setLogoRef(String logoRef) { this.logoRef = logoRef; }
    public String getBannerRef() { return bannerRef; }
    public void setBannerRef(String bannerRef) { this.bannerRef = bannerRef; }
    public int getMemberCount() { return memberCount; }
    public void setMemberCount(int memberCount) { this.memberCount = memberCount; }
}
