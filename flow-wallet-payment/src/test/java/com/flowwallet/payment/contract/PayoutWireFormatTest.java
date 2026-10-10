package com.flowwallet.payment.contract;

import com.flowwallet.payment.dto.PayoutAccountRequest;
import com.flowwallet.payment.dto.PayoutAccountResponse;
import com.flowwallet.payment.dto.PayoutPreflightRequest;
import com.flowwallet.payment.dto.PayoutPreflightResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freezes the field names of {@code PUT|GET /api/payments/payout-accounts} and
 * {@code POST /api/payments/payouts/preflight}, Payment Service's side of both calls.
 * Wallet Service keeps its own copies of the records: its {@code PayoutWireFormatTest} pins the payout-account names
 * and its {@code PayoutPreflightWireFormatTest} the preflight's.
 * See docs/adr/0002-module-boundaries.md.
 */
class PayoutWireFormatTest {
    private static final Set<String> REQUEST_FIELDS = Set.of("token", "tosAccepted");

    private static final Set<String> RESPONSE_FIELDS = Set.of("state", "last4", "currency");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theRequestReadsEveryFieldTheWalletSends() {
        // Guards a renamed request component: Jackson would leave it null, and every bank account request would get
        // a 400 for a field the wallet did send.
        PayoutAccountRequest request = mapper.readValue(
                """
                {"token": "btok_1", "tosAccepted": true}
                """,
                PayoutAccountRequest.class
        );

        assertThat(request).isEqualTo(new PayoutAccountRequest("btok_1", true));
        assertThat(mapper.readTree(mapper.writeValueAsString(request)).propertyNames())
                .containsExactlyInAnyOrderElementsOf(REQUEST_FIELDS);
    }

    @Test
    void theResponseCarriesExactlyTheFieldsTheWalletReads() {
        // Guards a renamed response component: the wallet would show the user no state for a bank account they set.
        PayoutAccountResponse response = new PayoutAccountResponse("READY", "2874", "PLN");

        assertThat(mapper.readTree(mapper.writeValueAsString(response)).propertyNames())
                .containsExactlyInAnyOrderElementsOf(RESPONSE_FIELDS);
    }

    @Test
    void thePreflightReadsEveryFieldTheWalletSendsAndAnswersWithExactlyTwo() {
        // Guards a renamed component: a currency the caller sends would be read as null, or a reserve answer would
        // reach the caller as null and hold every withdrawal.
        PayoutPreflightRequest request = mapper.readValue(
                """
                {"currency": "PLN", "amount": 75.00}
                """,
                PayoutPreflightRequest.class
        );

        assertThat(request).isEqualTo(new PayoutPreflightRequest("PLN", new BigDecimal("75.00")));
        String response = mapper.writeValueAsString(new PayoutPreflightResponse("READY", true));
        assertThat(mapper.readTree(response).propertyNames())
                .containsExactlyInAnyOrder("bankAccount", "reserveSufficient");
    }
}
