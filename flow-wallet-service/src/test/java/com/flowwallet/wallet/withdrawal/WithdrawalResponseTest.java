package com.flowwallet.wallet.withdrawal;

import com.flowwallet.wallet.api.AmountPrecision;
import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.balance.Wallet;
import com.flowwallet.wallet.enums.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WithdrawalResponseTest {
    private static final String REFERENCE = "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d";
    private static final String USER = "1f0c6d0e-5d4b-4c7e-9a2f-3b8e7d6c5a41";

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static String body(String currency, String amount, String balanceAfter) {
        return "{\"reference\":\"" + REFERENCE + "\",\"amount\":\"" + amount + "\",\"currency\":\"" + currency
                + "\",\"balanceAfter\":\"" + balanceAfter + "\"}";
    }

    private static Stream<Arguments> receipts() {
        return Stream.of(
                Arguments.of("PLN", "100.0000", "75", "75.0000", "25.0000", body("PLN", "75.00", "25.00")),
                Arguments.of("PLN", "100.5000", "0.5", "0.5000", "100.0000", body("PLN", "0.50", "100.00")),
                Arguments.of("PLN", "0.0100", "0.01", "0.0100", "0.0000", body("PLN", "0.01", "0.00")),
                Arguments.of("JPY", "5000.0000", "1000", "1000.0000", "4000.0000", body("JPY", "1000", "4000")),
                Arguments.of("JPY", "1000.0000", "1E+3", "1000.0000", "0.0000", body("JPY", "1000", "0"))
        );
    }

    @ParameterizedTest(name = "{0}: {2} of a balance of {1}")
    @MethodSource("receipts")
    void theFirstAnswerAndAReplayFromTheStoredRowRenderToTheSameBytes(
            String currency,
            String balance,
            String requested,
            String storedAmount,
            String storedBalanceAfter,
            String expectedBody
    ) {
        // Guards a first answer and a replay that differ on the wire, which tells a client that retried after a
        // lost 202 that something else happened. The first answer is built the way the handler builds it: a wallet
        // loaded from NUMERIC(19,4), the request amount brought to the ledger's scale and the in-memory debit. The
        // replay is built from a row as the database hands it back, with an id and a creation time that must not
        // reach the body. Both must print the amounts as strings at the currency's scale, a JPY one in whole units
        // and a drained wallet as 0.00 or 0, never as the ledger's 0.0000
        // (docs/adr/0030-amounts-in-responses-are-decimal-strings.md).
        Wallet wallet = Wallet.builder()
                .id(11L)
                .userId(USER)
                .currency(currency)
                .balance(new BigDecimal(balance))
                .build();
        BigDecimal amount = AmountPrecision.canonical(new BigDecimal(requested), currency);
        BigDecimal balanceBefore = wallet.debit(amount);
        BalanceHistory inMemory = BalanceHistory.withdrawal(wallet, REFERENCE, amount, balanceBefore);

        BalanceHistory stored = BalanceHistory.builder()
                .id(501L)
                .walletId(11L)
                .entryNo(3L)
                .transactionReference(REFERENCE)
                .type(TransactionType.WITHDRAWAL)
                .amount(new BigDecimal(storedAmount))
                .balanceBefore(new BigDecimal(balance))
                .balanceAfter(new BigDecimal(storedBalanceAfter))
                .createdAt(Instant.parse("2026-10-10T10:15:30.123456Z"))
                .build();

        byte[] first = mapper.writeValueAsBytes(WithdrawalResponse.of(inMemory, currency));
        byte[] replay = mapper.writeValueAsBytes(WithdrawalResponse.of(stored, currency));

        assertThat(first).isEqualTo(replay);
        assertThat(new String(replay, StandardCharsets.UTF_8)).isEqualTo(expectedBody);
    }

    @Test
    void theBodyHasNoStatusAndNoTimestampSoNothingInItChangesAsThePayoutProgresses() {
        // Guards a status or a time creeping into the receipt: a replay after the payout was paid or returned
        // would then differ from the first answer, and a client could not tell a replay from a new withdrawal.
        // The status is read at the withdrawal's own address instead.
        Wallet wallet = Wallet.builder()
                .id(11L)
                .userId(USER)
                .currency("PLN")
                .balance(new BigDecimal("100.0000"))
                .build();
        BigDecimal balanceBefore = wallet.debit(new BigDecimal("75.0000"));
        BalanceHistory debit = BalanceHistory.withdrawal(wallet, REFERENCE, new BigDecimal("75.0000"), balanceBefore);

        String json = mapper.writeValueAsString(WithdrawalResponse.of(debit, "PLN"));

        assertThat(mapper.readTree(json).propertyNames())
                .containsExactly("reference", "amount", "currency", "balanceAfter");
    }

    @ParameterizedTest(name = "{0} is refused")
    @EnumSource(value = TransactionType.class, names = "WITHDRAWAL", mode = EnumSource.Mode.EXCLUDE)
    void aReceiptIsBuiltOnlyFromAWithdrawalDebit(TransactionType type) {
        // Guards a receipt built from the wrong row. A withdrawal's return shares its reference with the debit
        // but is a credit, so a receipt built from it would show the money as if it had just left and a balance
        // higher than the one the withdrawal left; a transfer row belongs to another flow altogether. The factory
        // refuses every other type, a type added later included, rather than trusting each caller to pass the
        // right row.
        BalanceHistory row = BalanceHistory.builder()
                .id(502L)
                .walletId(11L)
                .entryNo(4L)
                .transactionReference(REFERENCE)
                .type(type)
                .amount(new BigDecimal("75.0000"))
                .balanceBefore(new BigDecimal("25.0000"))
                .balanceAfter(new BigDecimal("100.0000"))
                .build();

        assertThatThrownBy(() -> WithdrawalResponse.of(row, "PLN")).isInstanceOf(IllegalArgumentException.class);
    }
}
