package com.flowwallet.payment.contract;

import com.flowwallet.contract.event.WithdrawalRequestedEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads a withdrawal command as Wallet Service writes it, from a frozen copy of the JSON. Wallet Service pins the
 * field names on its side; this pins that Payment Service still reads a command an older wallet put on the topic.
 * See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
 */
class WithdrawalRequestedEventWireFormatTest {
    private static final String WRITTEN_BY_THE_WALLET = """
            {"eventId":"2f8a1c34-0000-4000-8000-000000000010","schemaVersion":1,\
            "transactionReference":"6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d","userId":"user-1",\
            "amount":75.0000,"currency":"PLN"}""";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aCommandAsTheWalletWritesItIsReadWithEveryTerm() {
        // Guards a renamed or retyped field on either side: Payment Service would dead-letter every debited
        // withdrawal, or bind a payout to terms the wallet never sent.
        WithdrawalRequestedEvent command = mapper.readValue(WRITTEN_BY_THE_WALLET, WithdrawalRequestedEvent.class);

        assertThat(command).isEqualTo(new WithdrawalRequestedEvent(
                "2f8a1c34-0000-4000-8000-000000000010",
                1,
                "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d",
                "user-1",
                new BigDecimal("75.0000"),
                "PLN"
        ));
    }
}
