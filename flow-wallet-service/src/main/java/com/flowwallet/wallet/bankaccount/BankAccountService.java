package com.flowwallet.wallet.bankaccount;

import com.flowwallet.wallet.deposit.PaymentServiceProblems;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.util.function.Supplier;

/**
 * Relays the caller's bank account requests to Payment Service, which holds the connected account and the bank
 * account. The wallet stores nothing and needs no wallet row: a bank account belongs to the user, whatever wallets
 * they hold. Log lines carry no user id and no token.
 * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankAccountService {
    private final PayoutAccountClient payoutAccounts;
    private final ObjectMapper objectMapper;

    public BankAccountResponse set(String userId, BankAccountRequest request) {
        var command = new PayoutAccountCommand(request.token(), request.tosAccepted());
        return relay(() -> payoutAccounts.set(userId, command), "Retry with the same token.");
    }

    public BankAccountResponse get(String userId) {
        return relay(() -> payoutAccounts.get(userId), "Retry.");
    }

    /**
     * @param retry how the caller retries: a write with the same token, which Payment Service answers from its row
     *              when the first attempt went through, or a read as it is
     */
    private BankAccountResponse relay(Supplier<PayoutAccountResult> call, String retry) {
        try {
            PayoutAccountResult result = call.get();
            if (result == null) {
                throw new RestClientException("Payment Service answered without a body");
            }
            return new BankAccountResponse(result.state(), result.last4(), result.currency());
        } catch (HttpClientErrorException e) {
            throw translate(e);
        } catch (HttpServerErrorException e) {
            log.warn("Payment Service answered {} for a bank account request", e.getStatusCode());
            throw new PayoutAccountUnavailableException(
                    "Payment Service could not complete the bank account request. " + retry
            );
        } catch (ResourceAccessException e) {
            // A timeout may come after Payment Service attached the token; the retry is answered from its row.
            log.warn("Payment Service did not answer a bank account request: {}", e.getMessage());
            throw new PayoutAccountUnavailableException("Payment Service is unavailable. " + retry);
        } catch (RestClientException e) {
            log.error("Payment Service gave an unreadable answer to a bank account request", e);
            throw new PayoutAccountUnavailableException(
                    "Payment Service gave an answer the wallet could not read. " + retry
            );
        }
    }

    private RuntimeException translate(HttpClientErrorException e) {
        if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
            return new BankAccountRejectedException(
                    PaymentServiceProblems.reasonOf(e, objectMapper, "Payment Service refused the bank account.")
            );
        }
        // Any other 4xx is a fault in the wallet's own request, not the caller's, so it is not passed through.
        log.error("Payment Service refused the wallet's own bank account request with {}", e.getStatusCode());
        return new PayoutAccountUnavailableException("Payment Service refused the request.");
    }
}
