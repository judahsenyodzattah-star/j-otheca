package com.jotheca.onboarding.model;

import com.jotheca.common.model.BaseEntity;
import com.jotheca.tenancy.model.LibraryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A self-service library registration request (spec §11):
 * submit → pay → platform approval → tenant provisioned (+ applicant becomes LIBRARY_ADMIN).
 */
@Entity
@Table(name = "library_onboarding_requests")
public class OnboardingRequest extends BaseEntity {

    public enum Status { SUBMITTED, PAYMENT_CONFIRMED, APPROVED, REJECTED }

    @Column(name = "applicant_user_id", nullable = false, length = 36)
    private String applicantUserId;

    @Column(name = "library_name", nullable = false, length = 200)
    private String libraryName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LibraryType type;

    @Column(length = 100)
    private String country;

    @Column(length = 100)
    private String city;

    @Column(length = 300)
    private String address;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "plan_code", nullable = false, length = 20)
    private String planCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status = Status.SUBMITTED;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "library_id", length = 36)
    private String libraryId;

    public String getApplicantUserId() { return applicantUserId; }
    public void setApplicantUserId(String applicantUserId) { this.applicantUserId = applicantUserId; }
    public String getLibraryName() { return libraryName; }
    public void setLibraryName(String libraryName) { this.libraryName = libraryName; }
    public LibraryType getType() { return type; }
    public void setType(LibraryType type) { this.type = type; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPlanCode() { return planCode; }
    public void setPlanCode(String planCode) { this.planCode = planCode; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public String getLibraryId() { return libraryId; }
    public void setLibraryId(String libraryId) { this.libraryId = libraryId; }
}
