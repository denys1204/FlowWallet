package com.flowwallet.payment.transaction.mapper;

import com.flowwallet.payment.dto.CreatePaymentIntentRequest;
import com.flowwallet.payment.dto.PaymentIntentResponse;
import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import com.flowwallet.payment.transaction.PaymentTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentEventMapperTest {
    private final PaymentEventMapper mapper = new PaymentEventMapperImpl();

    @Test
    void completedEventCarriesAmountAndCurrency() {
        PaymentCompletedEvent event = mapper.toPaymentCompletedEvent(transaction());

        assertThat(event.transactionReference()).isEqualTo("ref-1");
        assertThat(event.amount()).isEqualByComparingTo("50.00");
        assertThat(event.currency()).isEqualTo("USD");
        assertThat(event.walletId()).isEqualTo(1L);
        assertThat(event.userId()).isEqualTo("user-1");
        assertThat(event.completedAt()).isNotNull();
    }

    @Test
    void failedEventCarriesAmountCurrencyAndReason() {
        PaymentFailedEvent event = mapper.toPaymentFailedEvent(transaction(), "card declined");

        assertThat(event.transactionReference()).isEqualTo("ref-1");
        assertThat(event.amount()).isEqualByComparingTo("50.00");
        assertThat(event.currency()).isEqualTo("USD");
        assertThat(event.walletId()).isEqualTo(1L);
        assertThat(event.userId()).isEqualTo("user-1");
        assertThat(event.reason()).isEqualTo("card declined");
        assertThat(event.failedAt()).isNotNull();
    }

    @Test
    void mapsTransactionToPaymentIntentResponse() {
        PaymentTransaction tx = transaction();
        tx.markAsInitiated("pi_9", Map.of("clientSecret", "cs_9"));

        PaymentIntentResponse response = mapper.toResponse(tx);

        assertThat(response.paymentIntentId()).isEqualTo("pi_9");
        assertThat(response.transactionReference()).isEqualTo("ref-1");
        assertThat(response.providerData()).containsEntry("clientSecret", "cs_9");
    }

    private PaymentTransaction transaction() {
        CreatePaymentIntentRequest request = new CreatePaymentIntentRequest(
                "ref-1",
                new BigDecimal("50.00"),
                "USD",
                1L,
                "STRIPE"
        );
        return PaymentTransaction.create(request, "user-1");
    }
}
