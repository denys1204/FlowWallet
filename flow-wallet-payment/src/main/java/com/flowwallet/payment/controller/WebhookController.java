package com.flowwallet.payment.controller;

import com.flowwallet.payment.service.PaymentWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments/webhooks")
public class WebhookController {
    private final PaymentWebhookService webhookService;

    @PostMapping("/{provider}")
    public void handleWebhook(
            @PathVariable String provider,
            @RequestBody String payload,
            @RequestHeader Map<String, String> headers
    ) {
        webhookService.processWebhook(provider, payload, headers);
    }
}
