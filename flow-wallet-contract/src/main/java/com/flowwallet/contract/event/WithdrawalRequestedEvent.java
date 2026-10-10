package com.flowwallet.contract.event;

import java.math.BigDecimal;

/**
 * Written to Wallet Service's outbox in the transaction that debits the wallet and published from there on
 * {@code wallet.withdrawals}. Consumed by Payment Service, which accepts it as a payout under the reference and pays
 * it out; the outcome comes back as payout events on {@code payment.events}.
 * <p>
 * It carries the terms Payment Service binds to the reference and no wallet id. A redelivery with the same terms is
 * acknowledged; other terms under the same reference are a defect.
 * See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
 *
 * @param eventId              identifies this message; stable across redeliveries and topic replays
 * @param schemaVersion        payload version, bumped only if a change cannot be made additively
 * @param transactionReference the withdrawal's {@code Idempotency-Key}; also the payout's reference
 * @param userId               whose wallet was debited and whose bank account receives the payout
 * @param amount               amount in major currency units, as debited
 * @param currency             ISO 4217 code of the debited wallet
 */
public record WithdrawalRequestedEvent(
        String eventId,
        int schemaVersion,
        String transactionReference,
        String userId,
        BigDecimal amount,
        String currency
) {}
