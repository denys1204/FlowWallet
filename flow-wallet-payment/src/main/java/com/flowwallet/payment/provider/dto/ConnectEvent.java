package com.flowwallet.payment.provider.dto;

import org.jspecify.annotations.Nullable;

/**
 * A verified Connect webhook, reduced to its envelope: what it is about, never its data. The handler reads the payout
 * or the account itself, so nothing it acts on comes from the event's API version. The parser still reads the data
 * object, through {@code deserializeUnsafe} for another version, and one it cannot read at all is a 500.
 * See docs/adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md.
 */
public sealed interface ConnectEvent {
    String eventId();

    String type();

    /**
     * A payout on a connected account changed.
     */
    record PayoutChanged(String eventId, String type, String accountId, String payoutId) implements ConnectEvent {
    }

    /**
     * A connected account, or one of its bank accounts, changed.
     */
    record AccountChanged(String eventId, String type, String accountId) implements ConnectEvent {
    }

    /**
     * An event this service does not act on.
     *
     * @param defect why a handled type cannot be acted on, such as no connected account; null for a type not handled
     */
    record Ignored(String eventId, String type, @Nullable String defect) implements ConnectEvent {
    }
}
