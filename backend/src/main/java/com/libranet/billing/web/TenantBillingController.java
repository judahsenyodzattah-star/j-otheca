package com.libranet.billing.web;

import com.libranet.billing.service.PaymentService.CheckoutDetails;
import com.libranet.billing.service.TenantBillingService;
import com.libranet.billing.service.TenantBillingService.PaymentHistoryDto;
import com.libranet.billing.service.TenantBillingService.StorageAddonRequest;
import com.libranet.billing.service.TenantBillingService.SubscriptionViewDto;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-plane billing (Phase 9, spec §31): a LIBRARY_ADMIN sees the library's own subscription,
 * renews it, buys storage add-ons and reviews the payment history. Paying TENANT → PLATFORM only;
 * patron → library payments live on the fines rail.
 */
@RestController
@RequestMapping("/api/v1/tenant/billing")
public class TenantBillingController {

    private final TenantBillingService billing;

    public TenantBillingController(TenantBillingService billing) {
        this.billing = billing;
    }

    /** Current subscription + effective limits (plan + purchased storage add-ons). */
    @GetMapping("/subscription")
    public SubscriptionViewDto subscription() {
        return billing.current();
    }

    /** Start a renewal checkout for one more 30-day cycle at the current plan price. */
    @PostMapping("/renew")
    public CheckoutDetails renew() {
        return billing.renew();
    }

    /** Start a storage add-on checkout ({@code gb} × price-per-GB, stacked on confirm). */
    @PostMapping("/storage-addon")
    public CheckoutDetails storageAddon(@Valid @RequestBody StorageAddonRequest req) {
        return billing.buyStorageAddon(req.gb());
    }

    /** This library's payments (renewals, add-ons, patron-rail fine receipts). */
    @GetMapping("/payments")
    public List<PaymentHistoryDto> payments() {
        return billing.history();
    }
}
