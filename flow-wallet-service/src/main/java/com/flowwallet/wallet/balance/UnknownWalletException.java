package com.flowwallet.wallet.balance;

/**
 * A confirmed payment names a user and currency with no wallet. A deposit into a missing wallet is refused before
 * anything is charged, so this means a payment started by some other route, or a wallet that has disappeared.
 * The listener stores the event as {@code WALLET_NOT_FOUND} with its payload.
 * <p>
 * Not an {@code ApiException}, because a Kafka listener has no HTTP response.
 * See docs/adr/0016-error-model-and-status-codes.md.
 */
public class UnknownWalletException extends RuntimeException {
    public UnknownWalletException(String userId, String currency) {
        super("No %s wallet for user %s".formatted(currency, userId));
    }
}
