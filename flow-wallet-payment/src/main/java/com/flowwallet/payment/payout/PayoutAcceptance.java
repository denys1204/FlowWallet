package com.flowwallet.payment.payout;

import com.flowwallet.contract.event.WithdrawalRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns a withdrawal command into a payout. A separate bean from {@link WithdrawalCommandListener}, so the listener
 * classifies a unique violation after the transaction has rolled back, from a fresh one.
 * See docs/adr/0007-unique-constraints-decide.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutAcceptance {
    private final PayoutRepository payouts;
    private final PayoutTerms terms;
    private final PayoutOutboxService outbox;

    /**
     * Inserts the payout at {@code ACCEPTED}, due at once, and the unique reference decides a repeat. A command that
     * no longer meets the terms configuration sets is inserted and returned with {@code TERMS_REFUSED} in the same
     * transaction, with its {@code PayoutReturnedEvent}, so the debited money comes back by the one path it always
     * does. See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
     */
    @Transactional
    public void accept(WithdrawalRequestedEvent command) {
        Instant now = Instant.now();
        Payout payout = Payout.accepted(
                command.transactionReference(),
                command.userId(),
                command.amount(),
                command.currency().toUpperCase(Locale.ROOT),
                now
        );

        Optional<String> refusal = terms.refusal(command.currency(), command.amount());
        if (refusal.isPresent()) {
            payout.returnBeforeTransfer(PayoutReason.TERMS_REFUSED, now);
            payouts.saveAndFlush(payout);
            outbox.publishReturned(payout, now);
            log.warn("Returned withdrawal {}: {}", command.transactionReference(), refusal.get());
            return;
        }
        payouts.saveAndFlush(payout);
        log.info("Accepted withdrawal {}", command.transactionReference());
    }

    /**
     * Read after a unique violation, from a fresh transaction, to tell a redelivery from a conflicting command.
     */
    @Transactional(readOnly = true)
    public Optional<Payout> findByReference(String transactionReference) {
        return payouts.findByTransactionReference(transactionReference);
    }
}
