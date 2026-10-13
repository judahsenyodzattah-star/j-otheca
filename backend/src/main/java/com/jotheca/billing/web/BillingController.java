package com.jotheca.billing.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jotheca.billing.service.PaymentProvider;
import com.jotheca.billing.service.PaymentService;
import com.jotheca.common.error.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Payment webhooks + dev console. Webhook MUST verify the provider signature on the raw body. */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final PaymentService paymentService;
    private final PaymentProvider provider;
    private final ObjectMapper mapper = new ObjectMapper();

    public BillingController(PaymentService paymentService, PaymentProvider provider) {
        this.paymentService = paymentService;
        this.provider = provider;
    }

    @PostMapping("/webhooks/paystack")
    public ResponseEntity<Void> paystackWebhook(@RequestBody String rawBody,
                                                @RequestHeader(value = "x-paystack-signature", required = false) String signature) {
        if (!provider.validSignature(rawBody, signature)) {
            throw ApiException.unauthorized("BAD_SIGNATURE", "Invalid webhook signature");
        }
        try {
            JsonNode payload = mapper.readTree(rawBody);
            if ("charge.success".equals(payload.path("event").asText())) {
                String reference = payload.path("data").path("reference").asText(null);
                if (reference != null) {
                    paymentService.confirmByReference(reference);
                }
            }
        } catch (Exception e) {
            throw ApiException.badRequest("BAD_WEBHOOK", "Could not parse webhook payload");
        }
        return ResponseEntity.ok().build();
    }
}
