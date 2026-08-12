package com.libranet.billing.web;

import com.libranet.billing.model.Payment;
import com.libranet.billing.service.PaymentService;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** DEV ONLY: simulates a completed checkout against the mock provider. Not present in other profiles. */
@RestController
@Profile("dev")
@RequestMapping("/api/v1/billing/dev")
public class DevBillingController {

    private final PaymentService paymentService;

    public DevBillingController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/mark-paid")
    public Map<String, Object> markPaid(@RequestBody Map<String, String> body) {
        Payment payment = paymentService.confirmByReference(body.get("reference"));
        return Map.of("reference", payment.getProviderReference(), "status", payment.getStatus().name());
    }
}
