package com.flowwallet.wallet.deposit;

import java.util.Map;

/**
 * What the client needs to finish paying.
 * <p>
 * Nothing here may vary with time, such as a timestamp or a request id: a repeat of the same idempotency key
 * returns a byte-identical body. See docs/adr/0005-client-supplied-idempotency-keys.md.
 *
 * @param reference    the transaction, which is also the idempotency key the caller sent
 * @param provider     which provider's SDK the {@code providerData} belongs to
 * @param providerData opaque provider payload; for Stripe, the client secret
 */
public record DepositResponse(
        String reference,
        String provider,
        Map<String, Object> providerData
) {}
