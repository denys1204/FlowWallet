package com.flowwallet.wallet.deposit;

import java.math.BigDecimal;

/**
 * What the wallet asks Payment Service to do. A deliberate copy of that service's request shape rather than
 * a shared class: the two modules depend only on platform and contract, and flow-wallet-contract holds
 * events with zero dependencies by design. Putting a synchronous REST DTO there would rebuild the coupling
 * the module split removed. Four duplicated fields are the price of the boundary.
 *
 * @param transactionReference the caller's idempotency key, passed through unchanged
 * @param amount               amount in major units
 * @param currency             the wallet's own currency — never the caller's opinion of it
 * @param providerName         which provider to charge through
 */
record CreatePaymentIntentCommand(
        String transactionReference,
        BigDecimal amount,
        String currency,
        String providerName
) {}
