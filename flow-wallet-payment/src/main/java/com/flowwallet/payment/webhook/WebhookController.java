package com.flowwallet.payment.webhook;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The public webhook route. The body is read through {@link WebhookPayloadReader} rather than
 * {@code @RequestBody}, which would read an unauthenticated body of any size.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments/webhooks")
public class WebhookController {
    private final PaymentWebhookService webhookService;
    private final WebhookPayloadReader payloadReader;

    @PostMapping("/{provider}")
    public void handleWebhook(
            @PathVariable String provider,
            @RequestHeader Map<String, String> headers,
            HttpServletRequest request
    ) {
        webhookService.processWebhook(provider, payloadReader.read(request), headers);
    }
}
