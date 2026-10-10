package com.flowwallet.payment.webhook;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The webhook route for connected accounts, apart from the platform's, with its own signing secret. Its path has two
 * segments after {@code webhooks}, so the platform route's {@code /{provider}} never takes it, and the gateway's
 * {@code /api/payments/webhooks/**} route covers it. The body is read through {@link WebhookPayloadReader}, which caps
 * its size before anything else. See docs/adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments/webhooks")
public class ConnectWebhookController {
    private final ConnectWebhookService service;
    private final WebhookPayloadReader payloadReader;

    @PostMapping("/stripe/connect")
    public void handleConnectWebhook(@RequestHeader Map<String, String> headers, HttpServletRequest request) {
        service.process(payloadReader.read(request), headers);
    }
}
