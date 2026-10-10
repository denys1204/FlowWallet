package com.flowwallet.wallet.bankaccount;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Sets the caller's bank account for withdrawals. The client turns the bank details into a single-use Stripe bank
 * token with the publishable key, so FlowWallet never sees them. Checked here as Payment Service checks it, so a raw
 * bank number is refused before it leaves the wallet.
 * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 *
 * @param token       a Stripe bank account token; no problem detail quotes it
 *                    (docs/adr/0026-problem-details-never-quote-rejected-input.md)
 * @param tosAccepted whether the user accepted Stripe's Connected Account Agreement
 */
public record BankAccountRequest(
        @NotNull(message = "token is required")
        @Pattern(regexp = "btok_[A-Za-z0-9]{1,120}", message = "token must be a Stripe bank account token (btok_...)")
        String token,

        @NotNull(message = "tosAccepted is required")
        @AssertTrue(message = "tosAccepted must be true: payouts need Stripe's Connected Account Agreement")
        Boolean tosAccepted
) {
}
