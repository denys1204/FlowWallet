package com.flowwallet.payment.contract;

import com.flowwallet.payment.dto.CreatePaymentIntentRequest;
import com.flowwallet.payment.dto.PaymentIntentResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freezes the field names of {@code POST /api/payments/intent}, Payment Service's side of the call.
 * <p>
 * Wallet Service keeps its own copies of these records ({@code CreatePaymentIntentCommand},
 * {@code PaymentIntentResult}), kept in step by hand because the services share no DTO module. See
 * docs/adr/0002-module-boundaries.md. The wallet's {@code PaymentIntentWireFormatTest} pins the same names, so a
 * rename on one side fails a test instead of reaching the other side as a null.
 */
class PaymentIntentWireFormatTest {
    private static final Set<String> REQUEST_FIELDS = Set.of(
            "transactionReference", "amount", "currency", "providerName"
    );

    private static final Set<String> RESPONSE_FIELDS = Set.of(
            "providerData", "paymentIntentId", "transactionReference"
    );

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theRequestReadsEveryFieldTheWalletSends() {
        // Guards a renamed request component: Jackson would leave it null, and the wallet's deposit would get a
        // 400 for a field it did send.
        CreatePaymentIntentRequest request = mapper.readValue(
                """
                {"transactionReference": "ref-1", "amount": 50.00, "currency": "USD", "providerName": "STRIPE"}
                """,
                CreatePaymentIntentRequest.class
        );

        assertThat(request).isEqualTo(
                new CreatePaymentIntentRequest("ref-1", new BigDecimal("50.00"), "USD", "STRIPE")
        );
        assertThat(mapper.readTree(mapper.writeValueAsString(request)).propertyNames())
                .containsExactlyInAnyOrderElementsOf(REQUEST_FIELDS);
    }

    @Test
    void theResponseCarriesExactlyTheFieldsTheWalletReads() {
        // Guards a renamed response component: the wallet would read it as null and hand the client a deposit
        // without its client secret.
        PaymentIntentResponse response = new PaymentIntentResponse(
                Map.of("clientSecret", "cs_1"),
                "pi_1",
                "ref-1"
        );

        JsonNode json = mapper.readTree(mapper.writeValueAsString(response));

        assertThat(json.propertyNames()).containsExactlyInAnyOrderElementsOf(RESPONSE_FIELDS);
        assertThat(json.get("providerData").get("clientSecret").stringValue()).isEqualTo("cs_1");
    }
}
