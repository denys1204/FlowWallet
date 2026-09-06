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
 * The wallet's existence is proved before anything is charged. That ordering is the whole point of routing
 * deposits through this service: a payment begun anywhere else could complete against a wallet that does not
 * exist, leaving money taken with nowhere to put it. Here the caller gets a 404 while their card is still
 * untouched.
 * <p>
 * Nothing is persisted. Payment Service's idempotency already binds the reference to the terms it was used
 * for, and a second copy of that here would add a way for the two to disagree without adding a guarantee.
 * The only thing this service could usefully cache — the provider's client secret — is a bearer credential
 * with its own lifetime, and it has no business in a second database.
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
            // Covers 5xx, connection refused and both timeouts. Nothing was charged, so the caller may retry
            // with the same key -- which is exactly what the key is for.
            log.warn("Payment Service did not answer for reference {}: {}", reference, e.getMessage());
            throw new PaymentUnavailableException("Payment Service is unavailable. Retry with the same "
                    + "Idempotency-Key.");
        }
    }

    /**
     * One scoped read, in the repository's own transaction and nothing wider.
     * <p>
     * Deliberately not annotated: a {@code @Transactional} method called from inside the same class goes
     * through no proxy and so does nothing, which is worse than the honest absence — it reads as a guarantee
     * that is not there. Nothing here needs one anyway. It must also not lock: this read only decides whether
     * to make an outbound call, and holding a row lock across a network round-trip would queue every credit
     * to that wallet behind a provider that might be wedged.
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
        // Any other 4xx means the wallet sent something Payment Service did not expect, which is this
        // service's fault rather than the caller's. Passing it through would blame the wrong party.
        log.error("Payment Service refused the wallet's own request with {}: {}",
                e.getStatusCode(), e.getResponseBodyAsString());
        return new PaymentUnavailableException("Payment Service refused the request.");
    }

    /**
     * Pulls the reason out of the problem+json body, preferring the field-level {@code errors} over
     * {@code detail}.
     * <p>
     * That order matters more than it looks. A bean-validation failure renders {@code detail} as Spring's
     * generic "Invalid request content." and puts the only useful sentence — "Maximum deposit amount is
     * 10000.00" — in {@code errors}. Relaying {@code detail} alone would hand the caller a 400 that is
     * accurate and tells them nothing, which is worse than not relaying at all: it looks like an answer.
     * <p>
     * Both fields are read out of a shape this project's own exception handler produces on both sides, and
     * anything unexpected falls back to plain wording rather than throwing.
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
