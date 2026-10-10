package com.flowwallet.payment.payout;

import com.flowwallet.payment.dto.PayoutPreflightRequest;
import com.flowwallet.payment.dto.PayoutPreflightResponse;
import com.flowwallet.payment.provider.PayoutProvider;
import com.flowwallet.payment.provider.exception.PayoutLookupException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Answers whether a withdrawal can go ahead, so it can be refused or held before its debit. It judges the terms
 * first, without a network call; reads the caller's bank account; and asks Stripe for the platform's balance only
 * when a bank account is ready. The reserve check is not atomic: two withdrawals can both pass it, as can one whose
 * command is still on its way, and the transfer that finds the balance short returns its money with
 * {@code PLATFORM_FUNDS_SHORT}.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md and
 * docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutPreflight {
    private final PayoutTerms terms;
    private final PayoutAccountStore accounts;
    private final PayoutRepository payouts;
    private final PayoutProvider provider;

    public PayoutPreflightResponse check(String userId, PayoutPreflightRequest request) {
        if (!PayoutTerms.fitsIntegerDigits(request.amount())) {
            throw new PayoutTermsRefusedException("The amount is too large for a withdrawal");
        }
        terms.refusal(request.currency(), request.amount()).ifPresent(reason -> {
            throw new PayoutTermsRefusedException(reason);
        });

        boolean ready = accounts.findByUser(userId)
                .map(account -> account.isReadyFor(request.currency()))
                .orElse(false);
        if (!ready) {
            return new PayoutPreflightResponse(BankAccountState.NOT_SET.name(), null);
        }

        // The sum is read before the balance. A transfer that lands between the two reads is then counted on both
        // sides, which can only refuse more; read the other way round it would be counted on neither.
        BigDecimal owed = payouts.sumNotYetTransferred(request.currency());
        BigDecimal available;
        try {
            available = provider.availableBalance(request.currency());
        } catch (PayoutLookupException e) {
            // GlobalExceptionHandler logs the 502 with its cause.
            throw new PayoutReserveUnavailableException(e);
        }
        boolean sufficient = available.subtract(owed).compareTo(request.amount()) >= 0;
        if (!sufficient) {
            log.warn("The platform's {} reserve does not cover a withdrawal", request.currency());
        }
        return new PayoutPreflightResponse(BankAccountState.READY.name(), sufficient);
    }
}
