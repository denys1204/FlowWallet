package com.flowwallet.wallet.withdrawal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freezes the field names of {@code POST /api/payments/payouts/preflight}, Wallet Service's side of the call.
 * <p>
 * {@link PayoutPreflightCommand} and {@link PayoutPreflightResult} copy Payment Service's
 * {@code PayoutPreflightRequest} and {@code PayoutPreflightResponse} by hand, because the services share no DTO
 * module. See docs/adr/0002-module-boundaries.md. Payment Service's {@code PayoutWireFormatTest} pins the same names,
 * so a rename on one side fails a test instead of reaching the other side as a null.
 */
class PayoutPreflightWireFormatTest {
    private static final Set<String> COMMAND_FIELDS = Set.of("currency", "amount");

    private static final Set<String> RESULT_FIELDS = Set.of("bankAccount", "reserveSufficient");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theCommandSendsExactlyTheFieldsPaymentServiceReadsWithTheAmountAsANumber() {
        // Guards a renamed component: Payment Service would read the currency as null and refuse every withdrawal
        // with a 400 the caller cannot fix. Also guards the amount turning into a string or into scientific
        // notation, which would not be the number the withdrawal debits.
        String json = mapper.writeValueAsString(new PayoutPreflightCommand("PLN", new BigDecimal("100.0000")));

        JsonNode sent = mapper.readTree(json);
        assertThat(sent.propertyNames()).containsExactlyInAnyOrderElementsOf(COMMAND_FIELDS);
        assertThat(sent.get("currency").stringValue()).isEqualTo("PLN");
        assertThat(sent.get("amount").isNumber()).isTrue();
        assertThat(json).contains("\"amount\":100.0000");
    }

    @Test
    void theResultReadsEveryFieldPaymentServiceSendsAndANullReserve() {
        // Guards a renamed component: Jackson would leave it null, and every withdrawal would be held as an
        // unreadable answer; and a null reserve, which Payment Service sends while no bank account is ready, failing
        // to read.
        PayoutPreflightResult ready = mapper.readValue(
                """
                {"bankAccount": "READY", "reserveSufficient": true}
                """,
                PayoutPreflightResult.class
        );
        PayoutPreflightResult notSet = mapper.readValue(
                """
                {"bankAccount": "NOT_SET", "reserveSufficient": null}
                """,
                PayoutPreflightResult.class
        );

        assertThat(ready).isEqualTo(new PayoutPreflightResult("READY", true));
        assertThat(notSet).isEqualTo(new PayoutPreflightResult("NOT_SET", null));
    }

    @Test
    void theResultHasExactlyTheComponentsPaymentServiceSends() {
        // Guards a component added to the wallet's copy that Payment Service does not send: it would read as null on
        // every call, and the wallet would act on a field nobody fills.
        String json = mapper.writeValueAsString(new PayoutPreflightResult("READY", true));

        assertThat(mapper.readTree(json).propertyNames()).containsExactlyInAnyOrderElementsOf(RESULT_FIELDS);
    }
}
