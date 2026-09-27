package com.flowwallet.wallet.deposit;

import com.flowwallet.wallet.api.Currencies;
import com.flowwallet.wallet.api.WalletNotFoundException;
import com.flowwallet.wallet.balance.Wallet;
import com.flowwallet.wallet.balance.WalletRepository;
import com.flowwallet.wallet.dto.DepositRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.StringJoiner;

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
        } catch (RestClientException e) {
            // Covers 5xx, connection refused and both timeouts. Nothing was charged, so the same key may be
            // retried.
            log.warn("Payment Service did not answer for reference {}: {}", reference, e.getMessage());
            throw new PaymentUnavailableException(
                    "Payment Service is unavailable. Retry with the same Idempotency-Key."
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
            return new DepositRejectedException(detailFrom(e));
        }
        // Any other 4xx is a fault in the wallet's own request, not the caller's, so it is not passed through.
        log.error(
                "Payment Service refused the wallet's own request with {}: {}",
                e.getStatusCode(),
                e.getResponseBodyAsString()
        );
        return new PaymentUnavailableException("Payment Service refused the request.");
    }

    /**
     * Pulls the reason out of the problem+json body, preferring the field-level {@code errors} over
     * {@code detail}: for a bean-validation failure, {@code detail} is Spring's generic "Invalid request
     * content." and the crossed bound is only in {@code errors}. Anything unexpected falls back to plain wording
     * rather than throwing. See docs/adr/0013-deposit-initiation.md.
     */
    private String detailFrom(HttpClientErrorException e) {
        try {
            var body = objectMapper.readTree(e.getResponseBodyAsString());

            var errors = body.get("errors");
            if (errors != null && errors.isArray() && !errors.isEmpty()) {
                var joined = new StringJoiner("; ");
                errors.forEach(error -> joined.add(error.stringValue()));
                return joined.toString();
            }

            var detail = body.get("detail");
            if (detail != null && !detail.stringValue().isBlank()) {
                return detail.stringValue();
            }
        } catch (RuntimeException ignored) {
            // Falls through to the generic wording below.
        }
        return "Payment Service rejected the deposit.";
    }
}
