package com.flowwallet.wallet.transfer;

import com.flowwallet.wallet.api.AmountPrecision;
import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.balance.Wallet;
import com.flowwallet.wallet.enums.TransactionType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferResponseTest {
    private static final String REFERENCE = "7e1855b3-4d95-4a72-a0c9-ef0d78be2e44";
    private static final String SENDER = "1f0c6d0e-5d4b-4c7e-9a2f-3b8e7d6c5a41";
    private static final String RECIPIENT = "9a7b3c2d-1e0f-4a5b-8c6d-7e8f9a0b1c2d";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void aReplayRendersToTheSameBytesAsTheFirstAnswer() {
        // Guards a first answer and a replay that differ on the wire, which tells a client that retried after
        // a lost response that something else happened. The first answer is built the way the handler builds
        // it: a wallet loaded from NUMERIC(19,4) at 100.0000, a request amount of 25.00 brought to the
        // ledger's scale, and the in-memory leg. The replay is built from a row as the database hands it back,
        // with an id and a creation time that must not reach the body.
        Wallet sender = Wallet.builder()
                .id(11L)
                .userId(SENDER)
                .currency("USD")
                .balance(new BigDecimal("100.0000"))
                .build();
        BigDecimal amount = AmountPrecision.canonical(new BigDecimal("25.00"), "USD");
        BigDecimal balanceBefore = sender.debit(amount);
        BalanceHistory inMemory = BalanceHistory.transferOut(sender, REFERENCE, RECIPIENT, amount, balanceBefore);

        BalanceHistory stored = BalanceHistory.builder()
                .id(501L)
                .walletId(11L)
                .transactionReference(REFERENCE)
                .type(TransactionType.TRANSFER_OUT)
                .counterpartyUserId(RECIPIENT)
                .amount(new BigDecimal("25.0000"))
                .balanceBefore(new BigDecimal("100.0000"))
                .balanceAfter(new BigDecimal("75.0000"))
                .createdAt(Instant.parse("2026-09-26T10:15:30.123456Z"))
                .build();

        byte[] first = mapper.writeValueAsBytes(TransferResponse.of(inMemory, "USD"));
        byte[] replay = mapper.writeValueAsBytes(TransferResponse.of(stored, "USD"));

        assertThat(first).isEqualTo(replay);
        String json = new String(first, StandardCharsets.UTF_8);
        assertThat(json).contains("\"amount\":25.0000", "\"balanceAfter\":75.0000");
        assertThat(mapper.readTree(json).propertyNames())
                .containsExactlyInAnyOrder("reference", "to", "amount", "currency", "balanceAfter");
    }

    @Test
    void aReceiptIsNeverBuiltFromTheRecipientsLeg() {
        // The receiving leg's balance belongs to the recipient. A receipt built from it would show the sender
        // someone else's balance, so the factory refuses it rather than trusting every caller to pass the
        // right leg.
        Wallet recipient = Wallet.builder()
                .id(12L)
                .userId(RECIPIENT)
                .currency("USD")
                .balance(new BigDecimal("5.0000"))
                .build();
        BalanceHistory in = BalanceHistory.transferIn(
                recipient, REFERENCE, SENDER, new BigDecimal("25.0000"), new BigDecimal("5.0000")
        );

        assertThatThrownBy(() -> TransferResponse.of(in, "USD")).isInstanceOf(IllegalArgumentException.class);
    }
}
