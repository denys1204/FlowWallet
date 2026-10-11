package com.flowwallet.payment.support;

import com.stripe.net.Webhook;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Signs a webhook body the way Stripe does, so a test exercises the real signature check rather than skipping it.
 */
public final class StripeSignatures {
    private StripeSignatures() {
    }

    public static Map<String, String> header(String payload, String secret) {
        return header(payload, secret, Webhook.Util.getTimeNow());
    }

    public static Map<String, String> header(String payload, String secret, long timestamp) {
        try {
            String signature = Webhook.Util.computeHmacSha256(secret, timestamp + "." + payload);
            return Map.of("Stripe-Signature", "t=" + timestamp + ",v1=" + signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Could not sign the test webhook", e);
        }
    }
}
