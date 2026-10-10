package com.flowwallet.wallet.withdrawal;

import com.flowwallet.contract.event.WithdrawalRequestedEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freezes the shape of what Wallet Service puts onto {@code wallet.withdrawals}. Payment Service is deployed
 * separately and reads commands written by older versions of this code, so a renamed or dropped field would leave a
 * debited withdrawal that Payment Service cannot read. See
 * docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
 */
class WithdrawalRequestedEventWireFormatTest {
    private static final Set<String> FIELDS = Set.of(
            "eventId", "schemaVersion", "transactionReference", "userId", "amount", "currency"
    );

    private final ObjectMapper mapper = new ObjectMapper();

    private WithdrawalRequestedEvent command() {
        return new WithdrawalRequestedEvent(
                "2f8a1c34-0000-4000-8000-000000000010", 1,
                "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d",
                "user-1",
                new BigDecimal("75.00"), "PLN"
        );
    }

    @Test
    void theCommandCarriesExactlyTheAgreedFieldNamesAndNoWalletId() {
        // Guards a renamed or dropped field, which Payment Service would dead-letter, and a wallet id on the wire,
        // which ADR 0004 keeps inside the wallet.
        JsonNode json = mapper.readTree(mapper.writeValueAsString(command()));

        assertThat(json.propertyNames()).containsExactlyInAnyOrderElementsOf(FIELDS);
    }

    @Test
    void theCommandSurvivesARoundTripUnchanged() {
        // Payment Service binds these terms to the reference; a value changed on the way would pay out something
        // other than what the wallet debited.
        WithdrawalRequestedEvent original = command();

        assertThat(mapper.readValue(mapper.writeValueAsString(original), WithdrawalRequestedEvent.class))
                .isEqualTo(original);
    }
}
