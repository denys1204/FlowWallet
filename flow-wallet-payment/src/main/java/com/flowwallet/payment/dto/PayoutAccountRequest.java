package com.flowwallet.payment.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Sets the caller's bank account, relayed by Wallet Service for {@code X-User-Id}. The bank details stay at Stripe:
 * the client turns them into a single-use bank token with the publishable key and sends only the token.
 * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 *
 * @param token       a Stripe bank account token; the pattern keeps raw bank numbers out, and no problem detail quotes
 *                    it (docs/adr/0026-problem-details-never-quote-rejected-input.md)
 * @param tosAccepted whether the user accepted Stripe's Connected Account Agreement, which opening the account needs
 */
public record PayoutAccountRequest(
        @NotNull(message = "token is required")
        @Pattern(regexp = "btok_[A-Za-z0-9]{1,120}", message = "token must be a Stripe bank account token (btok_...)")
        String token,

        @NotNull(message = "tosAccepted is required")
        @AssertTrue(message = "tosAccepted must be true: payouts need Stripe's Connected Account Agreement")
        Boolean tosAccepted
) {
}
