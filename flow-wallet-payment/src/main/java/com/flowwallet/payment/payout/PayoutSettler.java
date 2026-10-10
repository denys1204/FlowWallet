package com.flowwallet.payment.payout;

import com.flowwallet.payment.provider.PayoutProvider;
import com.flowwallet.payment.provider.dto.ProviderPayout;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Settles a payout from its current status at Stripe, the one path the Connect webhook and the payout reconciler
 * share. It reads the payout through the pinned SDK with no transaction open, then applies it in
 * {@link PayoutStore#settle}. Log lines name the reference and Stripe's ids, never a user id.
 * See docs/adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutSettler {
    private final PayoutProvider provider;
    private final PayoutStore store;

    /**
     * @throws com.flowwallet.payment.provider.exception.PayoutLookupException when Stripe cannot be asked
     */
    public Settlement settle(String accountId, String payoutId) {
        ProviderPayout found = provider.retrievePayout(accountId, payoutId);
        Settlement settlement = store.settle(accountId, found, Instant.now());
        switch (settlement.kind()) {
            case MOVED -> log.info(
                    "Payout {} settled by Stripe's status {}: {} to {}",
                    settlement.reference(),
                    found.status(),
                    settlement.from(),
                    settlement.to()
            );
            case REPEATED ->
                    log.debug("Payout {} already shows Stripe's status {}", settlement.reference(), found.status());
            case CONTRADICTED -> log.warn(
                    "Payout {} is {}, which Stripe's status {} cannot move",
                    settlement.reference(),
                    settlement.from(),
                    found.status()
            );
            case DIFFERING_TERMS -> log.error(
                    "Stripe payout {} on account {} disagrees with payout {} on its {}; nothing was changed",
                    payoutId,
                    accountId,
                    settlement.reference(),
                    settlement.differences()
            );
            case UNKNOWN_PAYOUT -> log.warn("Stripe payout {} on account {} belongs to no payout", payoutId, accountId);
        }
        if (settlement.bankReset()) {
            log.info("Payout {} failed at its bank account, which is set back to NOT_SET", settlement.reference());
        }
        return settlement;
    }
}
