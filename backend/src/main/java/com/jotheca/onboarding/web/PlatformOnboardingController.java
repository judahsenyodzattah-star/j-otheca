package com.jotheca.onboarding.web;

import com.jotheca.billing.model.Payment;
import com.jotheca.billing.repo.PaymentRepository;
import com.jotheca.onboarding.dto.OnboardingDto;
import com.jotheca.onboarding.service.OnboardingService;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SUPER_ADMIN: review pipeline for new libraries (spec §11 verification & approval steps). */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformOnboardingController {

    private final OnboardingService onboardingService;
    private final PaymentRepository payments;

    public PlatformOnboardingController(OnboardingService onboardingService, PaymentRepository payments) {
        this.onboardingService = onboardingService;
        this.payments = payments;
    }

    @GetMapping("/onboarding")
    public Page<OnboardingDto> list(@RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "30") int size) {
        return onboardingService.platformList(status,
                PageRequest.of(Math.min(page, 500), Math.min(size, 200), Sort.by("createdAt").descending()));
    }

    @PostMapping("/onboarding/{id}/approve")
    public OnboardingDto approve(@PathVariable String id) {
        return onboardingService.approve(id);
    }

    @PostMapping("/onboarding/{id}/reject")
    public OnboardingDto reject(@PathVariable String id, @RequestBody(required = false) RejectBody body) {
        return onboardingService.reject(id, body == null ? null : body.reason());
    }

    public record RejectBody(@Size(max = 500) String reason) {}

    @GetMapping("/payments")
    public List<PaymentView> payments() {
        return payments.findAll().stream().sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(PaymentView::from).toList();
    }

    public record PaymentView(String reference, String provider, String status, long amountMinor,
                              String currency, String purpose, java.time.Instant paidAt) {
        static PaymentView from(Payment p) {
            return new PaymentView(p.getProviderReference(), p.getProvider().name(), p.getStatus().name(),
                    p.getAmountMinor(), p.getCurrency(), p.getPurpose().name(), p.getPaidAt());
        }
    }
}
