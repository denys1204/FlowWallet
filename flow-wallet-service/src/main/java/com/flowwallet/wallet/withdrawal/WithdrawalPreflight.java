package com.flowwallet.wallet.withdrawal;

import com.flowwallet.wallet.deposit.PaymentServiceProblems;
import com.flowwallet.wallet.deposit.PaymentUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

/**
 * Asks Payment Service whether it can pay the withdrawal out, before anything is written: its terms, the caller's bank
 * account and the platform's reserve. Every answer but a usable one ends the request with nothing written, so a
 * retry with the same key is safe; an answer the wallet does not know is never read as a ready or missing bank
 * account, the latter of which would spend the key. Log lines carry the reference, never the user id or a body.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class WithdrawalPreflight {
    private static final String RETRY = " Nothing was debited. Retry with the same Idempotency-Key.";

    private final PayoutClient payouts;
    private final ObjectMapper objectMapper;

    /**
     * @throws WithdrawalRejectedException     if Payment Service refuses the terms
     * @throws WithdrawalsUnavailableException if the bank account is ready but the reserve is short
     * @throws PaymentUnavailableException     if Payment Service cannot be asked or gives no usable answer
     */
    BankAccountState check(WithdrawalTerms terms) {
        PayoutPreflightResult result;
        try {
            result = payouts.preflight(terms.userId(), new PayoutPreflightCommand(terms.currency(), terms.amount()));
            if (result == null) {
                throw new RestClientException("Payment Service answered without a body");
            }
        } catch (HttpClientErrorException e) {
            throw translate(e, terms);
        } catch (HttpServerErrorException e) {
            log.warn(
                    "Payment Service answered {} to the preflight of withdrawal {}",
                    e.getStatusCode(),
                    terms.reference()
            );
            throw new PaymentUnavailableException("Payment Service could not check the withdrawal." + RETRY);
        } catch (ResourceAccessException e) {
            log.warn(
                    "Payment Service did not answer the preflight of withdrawal {}: {}",
                    terms.reference(),
                    e.getMessage()
            );
            throw new PaymentUnavailableException("Payment Service is unavailable." + RETRY);
        } catch (RestClientException e) {
            log.error(
                    "Payment Service gave an unreadable answer to the preflight of withdrawal {}",
                    terms.reference(),
                    e
            );
            throw unreadable();
        }
        return read(result, terms);
    }

    private BankAccountState read(PayoutPreflightResult result, WithdrawalTerms terms) {
        if ("NOT_SET".equals(result.bankAccount())) {
            return BankAccountState.NOT_SET;
        }
        if (!"READY".equals(result.bankAccount()) || result.reserveSufficient() == null) {
            log.error(
                    "Payment Service gave an answer the wallet does not know to the preflight of withdrawal {}",
                    terms.reference()
            );
            throw unreadable();
        }
        if (!result.reserveSufficient()) {
            throw new WithdrawalsUnavailableException();
        }
        return BankAccountState.READY;
    }

    private RuntimeException translate(HttpClientErrorException e, WithdrawalTerms terms) {
        if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
            return new WithdrawalRejectedException(
                    PaymentServiceProblems.reasonOf(e, objectMapper, "Payment Service refused the withdrawal.")
            );
        }
        // Any other 4xx is a fault in the wallet's own request, not the caller's, so it is not passed through.
        log.error(
                "Payment Service refused the wallet's own preflight of withdrawal {} with {}",
                terms.reference(),
                e.getStatusCode()
        );
        return new PaymentUnavailableException("Payment Service refused the request. Nothing was debited.");
    }

    private static PaymentUnavailableException unreadable() {
        return new PaymentUnavailableException("Payment Service gave an answer the wallet could not read." + RETRY);
    }
}
