package com.libranet.circulation.model;

import com.libranet.common.model.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Per-library circulation rules (spec §20). Created lazily with defaults; LIBRARY_ADMIN may tune. */
@Entity
@Table(name = "loan_policies")
public class LoanPolicy extends TenantScopedEntity {

    @Column(name = "loan_days", nullable = false)
    private int loanDays = 14;

    @Column(name = "renew_days", nullable = false)
    private int renewDays = 14;

    @Column(name = "max_active_loans", nullable = false)
    private int maxActiveLoans = 5;

    @Column(name = "max_renewals", nullable = false)
    private int maxRenewals = 2;

    @Column(name = "max_reservations", nullable = false)
    private int maxReservations = 3;

    @Column(name = "pickup_deadline_hours", nullable = false)
    private int pickupDeadlineHours = 48;

    @Column(name = "grace_days", nullable = false)
    private int graceDays;

    /** Late-fee per overdue day, in minor units (pesewas for GHS). 0 = this library doesn't fine. */
    @Column(name = "fine_per_day_minor", nullable = false)
    private long finePerDayMinor = 200;

    /** Default charge for a lost/unreturned item. */
    @Column(name = "replacement_fee_minor", nullable = false)
    private long replacementFeeMinor = 50000;

    @Column(nullable = false, length = 3)
    private String currency = "GHS";

    public int getLoanDays() { return loanDays; }
    public void setLoanDays(int loanDays) { this.loanDays = loanDays; }
    public int getRenewDays() { return renewDays; }
    public void setRenewDays(int renewDays) { this.renewDays = renewDays; }
    public int getMaxActiveLoans() { return maxActiveLoans; }
    public void setMaxActiveLoans(int maxActiveLoans) { this.maxActiveLoans = maxActiveLoans; }
    public int getMaxRenewals() { return maxRenewals; }
    public void setMaxRenewals(int maxRenewals) { this.maxRenewals = maxRenewals; }
    public int getMaxReservations() { return maxReservations; }
    public void setMaxReservations(int maxReservations) { this.maxReservations = maxReservations; }
    public int getPickupDeadlineHours() { return pickupDeadlineHours; }
    public void setPickupDeadlineHours(int pickupDeadlineHours) { this.pickupDeadlineHours = pickupDeadlineHours; }
    public int getGraceDays() { return graceDays; }
    public void setGraceDays(int graceDays) { this.graceDays = graceDays; }
    public long getFinePerDayMinor() { return finePerDayMinor; }
    public void setFinePerDayMinor(long finePerDayMinor) { this.finePerDayMinor = finePerDayMinor; }
    public long getReplacementFeeMinor() { return replacementFeeMinor; }
    public void setReplacementFeeMinor(long replacementFeeMinor) { this.replacementFeeMinor = replacementFeeMinor; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}
