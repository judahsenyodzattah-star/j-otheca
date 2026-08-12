package com.libranet.billing.model;

import com.libranet.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/** Platform payment record (spec §31: Library → LibraNet). Patron→Library payments are a separate system (Phase 9). */
@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {

    public enum Status { INITIATED, PAID, FAILED, CANCELLED }
    public enum Purpose { SUBSCRIPTION, STORAGE_ADDON, FINE }

    @Column(name = "payer_user_id", nullable = false, length = 36)
    private String payerUserId;

    /** Tenant the payment belongs to (set for renewals/add-ons/fines; platform sign-ups leave it null). */
    @Column(name = "tenant_id", length = 36)
    private String tenantId;

    /** Patron-rail link (Phase 9): the fine this payment settles. */
    @Column(name = "fine_id", length = 36)
    private String fineId;

    @Column(name = "onboarding_request_id", length = 36)
    private String onboardingRequestId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Purpose purpose = Purpose.SUBSCRIPTION;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Provider provider;

    public enum Provider { PAYSTACK, MOCK }

    @Column(name = "provider_reference", nullable = false, length = 80)
    private String providerReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.INITIATED;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    public String getPayerUserId() { return payerUserId; }
    public void setPayerUserId(String payerUserId) { this.payerUserId = payerUserId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getFineId() { return fineId; }
    public void setFineId(String fineId) { this.fineId = fineId; }
    public String getOnboardingRequestId() { return onboardingRequestId; }
    public void setOnboardingRequestId(String onboardingRequestId) { this.onboardingRequestId = onboardingRequestId; }
    public long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Purpose getPurpose() { return purpose; }
    public void setPurpose(Purpose purpose) { this.purpose = purpose; }
    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }
    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String providerReference) { this.providerReference = providerReference; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
}
