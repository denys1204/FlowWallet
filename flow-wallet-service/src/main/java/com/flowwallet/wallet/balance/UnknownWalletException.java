package com.flowwallet.wallet.balance;

/**
 * A confirmed payment names a user and currency the caller holds no wallet in.
 * <p>
 * This should be unreachable in practice: a deposit is initiated through the wallet, which refuses with 404
 * before any money moves if the wallet does not exist. Reaching here means something got past that — a
 * payment started by some other route, or a wallet that vanished. It is recorded rather than dropped, with
 * the payload kept, so the event can be replayed once the wallet exists.
 * <p>
 * Deliberately not an {@code ApiException}: a Kafka listener has no HTTP response, and a status code
 * attached here would mean nothing to whoever reads it next.
 */
public class UnknownWalletException extends RuntimeException {
    public UnknownWalletException(String userId, String currency) {
        super("No %s wallet for user %s".formatted(currency, userId));
    }
}
