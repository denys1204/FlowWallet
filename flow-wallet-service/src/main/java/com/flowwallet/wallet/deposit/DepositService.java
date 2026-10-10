package com.flowwallet.wallet.deposit;

import com.flowwallet.wallet.api.Currencies;
import com.flowwallet.wallet.api.WalletNotFoundException;
import com.flowwallet.wallet.balance.Wallet;
import com.flowwallet.wallet.balance.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * Starts a deposit into a wallet the caller actually holds.
 * <p>
 * The wallet is found before Payment Service is called, so a missing wallet costs a 404 with the card untouched.
 * Nothing about the deposit is stored here: Payment Service's row is the only record of the key's terms.
 * See docs/adr/0013-deposit-initiation.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepositService {
    private final WalletRepository wallets;
    private final PaymentIntentClient payments;
    private final WalletPaymentProperties properties;
    private final ObjectMapper objectMapper;

    public DepositResponse start(String userId, String currency, String idempotencyKey, DepositRequest request) {
        String code = Currencies.normalise(currency);
        String reference = idempotencyKey.toLowerCase(Locale.ROOT);

        Wallet wallet = requireWallet(userId, code);

        var command = new CreatePaymentIntentCommand(
                reference, request.amount(), wallet.getCurrency(), properties.getProviderName()
        );

        try {
            PaymentIntentResult result = payments.createIntent(userId, command);
            return new DepositResponse(
                    result.transactionReference(), properties.getProviderName(), result.providerData()
            );
        } catch (HttpClientErrorException e) {
            throw translate(e);
        } catch (HttpServerErrorException e) {
            // Payment Service is up and answered: its call to the provider failed, or it hit a defect. Nothing was
            // charged, so the same key may be retried.
            log.warn("Payment Service answered {} for reference {}", e.getStatusCode(), reference);
            throw new PaymentUnavailableException(
                    "Payment Service could not start the payment. Nothing was charged. Retry with the same "
                            + "Idempotency-Key."
            );
        } catch (ResourceAccessException e) {
            // Connection refused or either timeout. Nothing was charged, so the same key may be retried.
            log.warn("Payment Service did not answer for reference {}: {}", reference, e.getMessage());
            throw new PaymentUnavailableException(
                    "Payment Service is unavailable. Retry with the same Idempotency-Key."
            );
        } catch (RestClientException e) {
            // An answer the wallet cannot read, such as an unknown status or a body that does not bind.
            log.error("Payment Service gave an unreadable answer for reference {}", reference, e);
            throw new PaymentUnavailableException(
                    "Payment Service gave an answer the wallet could not read. Retry with the same Idempotency-Key."
            );
        }
    }

    /**
     * One non-locking read in the repository's own transaction. A lock or a wider transaction here would be held
     * across the call to Payment Service, and {@code @Transactional} on this self-invoked method would do nothing.
     * See docs/adr/0006-short-transactions-across-bean-boundaries.md.
     */
    private Wallet requireWallet(String userId, String currency) {
        return wallets.findByUserIdAndCurrency(userId, currency)
                .orElseThrow(() -> new WalletNotFoundException(currency));
    }

    private RuntimeException translate(HttpClientErrorException e) {
        if (e.getStatusCode() == HttpStatus.CONFLICT) {
            return new ConflictingDepositException();
        }
        if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
            return new DepositRejectedException(
                    PaymentServiceProblems.reasonOf(e, objectMapper, "Payment Service rejected the deposit.")
            );
        }
        // Any other 4xx is a fault in the wallet's own request, not the caller's, so it is not passed through.
        log.error(
                "Payment Service refused the wallet's own request with {}: {}",
                e.getStatusCode(),
                e.getResponseBodyAsString()
        );
        return new PaymentUnavailableException("Payment Service refused the request.");
    }
}
