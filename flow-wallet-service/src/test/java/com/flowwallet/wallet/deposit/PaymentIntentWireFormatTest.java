package com.flowwallet.wallet.deposit;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freezes the field names of {@code POST /api/payments/intent}, Wallet Service's side of the call.
 * <p>
 * {@link CreatePaymentIntentCommand} and {@link PaymentIntentResult} copy Payment Service's
 * {@code CreatePaymentIntentRequest} and {@code PaymentIntentResponse} by hand, because the services share no DTO
 * module. See docs/adr/0002-module-boundaries.md. Payment Service's {@code PaymentIntentWireFormatTest} pins the
 * same names, so a rename on one side fails a test instead of reaching the other side as a null.
 */
class PaymentIntentWireFormatTest {
    private static final Set<String> COMMAND_FIELDS = Set.of(
            "transactionReference", "amount", "currency", "providerName"
    );

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theCommandSendsExactlyTheFieldsPaymentServiceReads() {
        // Guards a renamed command component: Payment Service would read the field as null and refuse every
        // deposit with a 400 the caller cannot fix.
        var command = new CreatePaymentIntentCommand("ref-1", new BigDecimal("50.00"), "USD", "STRIPE");

        assertThat(mapper.readTree(mapper.writeValueAsString(command)).propertyNames())
                .containsExactlyInAnyOrderElementsOf(COMMAND_FIELDS);
    }

    @Test
    void theResultReadsEveryFieldPaymentServiceAnswers() {
        // Guards a renamed result component: Jackson would leave it null, and the client would get a deposit
        // response without its client secret or reference.
        PaymentIntentResult result = mapper.readValue(
                """
                {"providerData": {"clientSecret": "cs_1"}, "paymentIntentId": "pi_1", "transactionReference": "ref-1"}
                """,
                PaymentIntentResult.class
        );

        assertThat(result).isEqualTo(new PaymentIntentResult(Map.of("clientSecret", "cs_1"), "pi_1", "ref-1"));
    }
}
