package com.flowwallet.wallet.deposit;

import java.math.BigDecimal;

/**
 * What the wallet asks Payment Service to do: a copy of its {@code CreatePaymentIntentRequest}, kept in step by
 * hand. See docs/adr/0002-module-boundaries.md.
 *
 * @param transactionReference the caller's idempotency key in lower case, which Payment Service also sends to
 *                             Stripe as its idempotency key
 * @param amount               amount in major units
 * @param currency             the wallet's own currency, never one the caller supplied
 */
record CreatePaymentIntentCommand(
        String transactionReference,
        BigDecimal amount,
        String currency,
        String providerName
) {}
