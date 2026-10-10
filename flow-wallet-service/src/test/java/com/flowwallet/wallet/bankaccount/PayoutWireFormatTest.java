package com.flowwallet.wallet.bankaccount;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freezes the field names of {@code PUT|GET /api/payments/payout-accounts}, Wallet Service's side of the relay.
 * Payment Service's {@code PayoutWireFormatTest} pins the same names on its records. See
 * docs/adr/0002-module-boundaries.md.
 */
class PayoutWireFormatTest {
    private static final Set<String> COMMAND_FIELDS = Set.of("token", "tosAccepted");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theCommandSendsExactlyTheFieldsPaymentServiceReads() {
        // Guards a renamed component: Payment Service would read it as null and refuse every bank account with a 400
        // the user cannot fix.
        String json = mapper.writeValueAsString(new PayoutAccountCommand("btok_1", true));

        assertThat(mapper.readTree(json).propertyNames()).containsExactlyInAnyOrderElementsOf(COMMAND_FIELDS);
    }

    @Test
    void theResultReadsEveryFieldPaymentServiceSends() {
        // Guards a renamed component: the wallet would tell the user nothing about a bank account they set.
        PayoutAccountResult result = mapper.readValue(
                """
                {"state": "READY", "last4": "2874", "currency": "PLN"}
                """,
                PayoutAccountResult.class
        );

        assertThat(result).isEqualTo(new PayoutAccountResult("READY", "2874", "PLN"));
    }
}
