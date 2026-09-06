package com.flowwallet.wallet.deposit;

import java.util.Map;

/**
 * What the client needs to finish paying.
 * <p>
 * Nothing here varies with time — no timestamp, no request id — and that is a contract rule rather than an
 * accident: a repeat of the same idempotency key must return a byte-identical body, and a clock in the
 * response would break that for no benefit.
 *
 * @param reference    the transaction, which is also the idempotency key the caller sent
 * @param provider     which provider's SDK the {@code providerData} belongs to
 * @param providerData opaque provider payload — for Stripe, the client secret
 */
public record DepositResponse(
        String reference,
        String provider,
        Map<String, Object> providerData
) {}
