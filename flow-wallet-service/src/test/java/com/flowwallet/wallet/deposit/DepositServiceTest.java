package com.flowwallet.wallet.deposit;

import com.flowwallet.wallet.api.WalletNotFoundException;
import com.flowwallet.wallet.balance.Wallet;
import com.flowwallet.wallet.balance.WalletRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DepositServiceTest {
    private final WalletRepository wallets = mock(WalletRepository.class);
    private final PaymentIntentClient payments = mock(PaymentIntentClient.class);
    private final WalletPaymentProperties properties = new WalletPaymentProperties();
    private final DepositService service =
            new DepositService(wallets, payments, properties, new ObjectMapper());

    private static final String KEY = "7E1855B3-4D95-4A72-A0C9-EF0D78BE2E44";
    private final DepositRequest request = new DepositRequest(new BigDecimal("25.00"));

    private void walletExists() {
        when(wallets.findByUserIdAndCurrency("gina", "USD")).thenReturn(Optional.of(Wallet.open("gina", "USD")));
    }

    private HttpClientErrorException refusal(HttpStatus status, String body) {
        return HttpClientErrorException.create(
                status,
                status.getReasonPhrase(),
                HttpHeaders.EMPTY,
                body.getBytes(),
                null
        );
    }

    @Test
    void aMissingWalletIsRefusedBeforeAnythingIsCharged() {
        // The whole reason deposits are routed through the wallet: a payment begun elsewhere could complete
        // against a wallet that does not exist, leaving money taken with nowhere to put it.
        when(wallets.findByUserIdAndCurrency("gina", "USD")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start("gina", "USD", KEY, request))
                .isInstanceOf(WalletNotFoundException.class);

        verifyNoInteractions(payments);
    }

    @Test
    void theKeyBecomesTheReferenceInLowerCaseAndTheCurrencyComesFromTheWallet() {
        walletExists();
        when(payments.createIntent(any(), any()))
                .thenReturn(new PaymentIntentResult(Map.of("clientSecret", "cs_1"), "pi_1", KEY.toLowerCase()));

        service.start("gina", "usd", KEY, request);

        ArgumentCaptor<CreatePaymentIntentCommand> sent = ArgumentCaptor.forClass(CreatePaymentIntentCommand.class);
        verify(payments).createIntent(eq("gina"), sent.capture());
        // Case-normalised, or two spellings of one key would become two references and a retry would start a
        // second payment.
        assertThat(sent.getValue().transactionReference()).isEqualTo(KEY.toLowerCase());
        // The currency is the wallet's own, never the caller's spelling of it.
        assertThat(sent.getValue().currency()).isEqualTo("USD");
        assertThat(sent.getValue().providerName()).isEqualTo("STRIPE");
    }

    @Test
    void aConflictFromPaymentBecomesAConflictNamingTheIdempotencyKey() {
        // Payment's own wording names a transaction reference the caller never sent.
        walletExists();
        when(payments.createIntent(any(), any()))
                .thenThrow(refusal(HttpStatus.CONFLICT, "{\"detail\":\"Transaction reference x was already paid\"}"));

        assertThatThrownBy(() -> service.start("gina", "USD", KEY, request))
                .isInstanceOf(ConflictingDepositException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void aRejectedAmountRelaysTheReasonRatherThanTheGenericDetail() {
        // A bean-validation failure renders detail as "Invalid request content." and puts the only useful
        // sentence in errors. Relaying detail alone would be a 400 that looks like an answer and is not.
        walletExists();
        when(payments.createIntent(any(), any())).thenThrow(refusal(
                HttpStatus.BAD_REQUEST,
                "{\"detail\":\"Invalid request content.\",\"errors\":[\"amount Maximum deposit amount is 10000.00\"]}"
        ));

        assertThatThrownBy(() -> service.start("gina", "USD", KEY, request))
                .isInstanceOf(DepositRejectedException.class)
                .hasMessageContaining("Maximum deposit amount is 10000.00");
    }

    @Test
    void aProviderRefusalRelaysPaymentServicesDetailWithItsAdviceOnTheKey() {
        // Guards the detail being replaced with the wallet's own wording. Only Payment Service knows that the key
        // is now bound to the refused request, and a caller told nothing would retry under the same key forever.
        walletExists();
        when(payments.createIntent(any(), any())).thenThrow(refusal(
                HttpStatus.BAD_REQUEST,
                "{\"detail\":\"The payment provider refused the payment: Amount too small. Correct the request "
                        + "and retry with a new Idempotency-Key; this one stays bound to the refused request.\"}"
        ));

        assertThatThrownBy(() -> service.start("gina", "USD", KEY, request))
                .isInstanceOf(DepositRejectedException.class)
                .hasMessageContaining("Amount too small")
                .hasMessageContaining("new Idempotency-Key");
    }

    @Test
    void aPaymentServiceThatDoesNotAnswerIsABadGateway() {
        // Guards a connection failure or timeout losing its same-key advice: nothing was charged.
        walletExists();
        when(payments.createIntent(any(), any())).thenThrow(new ResourceAccessException("read timed out"));

        assertThatThrownBy(() -> service.start("gina", "USD", KEY, request))
                .isInstanceOf(PaymentUnavailableException.class)
                .hasMessage("Payment Service is unavailable. Retry with the same Idempotency-Key.");
    }

    @Test
    void aFailureThatPaymentServiceAnsweredIsNotCalledUnavailability() {
        // Guards a 5xx that Payment Service sent itself, such as a 502 for a provider outage, being reported as
        // Payment Service being unavailable, which sends an operator to the wrong service.
        walletExists();
        when(payments.createIntent(any(), any())).thenThrow(HttpServerErrorException.create(
                HttpStatus.BAD_GATEWAY,
                "Bad Gateway",
                HttpHeaders.EMPTY,
                "{\"detail\":\"Stripe payment initiation failed\"}".getBytes(),
                null
        ));

        assertThatThrownBy(() -> service.start("gina", "USD", KEY, request))
                .isInstanceOf(PaymentUnavailableException.class)
                .hasMessage(
                        "Payment Service could not start the payment. Nothing was charged. Retry with the same "
                                + "Idempotency-Key."
                );
    }

    @Test
    void anAnswerTheWalletCannotReadIsNeitherUnavailabilityNorAProviderFailure() {
        // Guards the last catch being folded into one of the others: a body that does not bind or an unknown
        // status means Payment Service answered, so blaming its availability or its provider call misleads the
        // operator, and the caller still needs the same-key advice.
        walletExists();
        when(payments.createIntent(any(), any())).thenThrow(new RestClientException("could not extract response"));

        assertThatThrownBy(() -> service.start("gina", "USD", KEY, request))
                .isInstanceOf(PaymentUnavailableException.class)
                .hasMessage(
                        "Payment Service gave an answer the wallet could not read. Retry with the same "
                                + "Idempotency-Key."
                );
    }

    @Test
    void anUnexpectedRefusalIsNotBlamedOnTheCaller() {
        // A 4xx the wallet did not expect means the wallet sent something wrong, not the caller. Passing it
        // through would blame the wrong party.
        walletExists();
        when(payments.createIntent(any(), any())).thenThrow(refusal(HttpStatus.UNAUTHORIZED, "{}"));

        assertThatThrownBy(() -> service.start("gina", "USD", KEY, request))
                .isInstanceOf(PaymentUnavailableException.class);
    }
}
