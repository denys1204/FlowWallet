package com.flowwallet.wallet.withdrawal;

import com.flowwallet.wallet.balance.Wallet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class WithdrawalStatusResponseTest {
    private static final String REFERENCE = "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d";
    private static final String USER = "1f0c6d0e-5d4b-4c7e-9a2f-3b8e7d6c5a41";
    private static final BigDecimal STORED_AMOUNT = new BigDecimal("75.0000");

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static Wallet wallet(String currency) {
        return Wallet.builder()
                .id(11L)
                .userId(USER)
                .currency(currency)
                .balance(new BigDecimal("100.0000"))
                .build();
    }

    private static Withdrawal pending() {
        return Withdrawal.pending(wallet("PLN"), REFERENCE, STORED_AMOUNT);
    }

    private static Withdrawal refused(WithdrawalRefusalReason reason) {
        return Withdrawal.refused(wallet("PLN"), REFERENCE, STORED_AMOUNT, reason);
    }

    private static Withdrawal returning(String reasonCode) {
        Withdrawal withdrawal = pending();
        withdrawal.markReturnPending(reasonCode);
        return withdrawal;
    }

    private static Withdrawal returned(String reasonCode) {
        Withdrawal withdrawal = returning("PAYOUT_FAILED");
        withdrawal.markReturned(reasonCode);
        return withdrawal;
    }

    private static Stream<Arguments> withdrawalsWithAReason() {
        return Stream.of(
                Arguments.of(refused(WithdrawalRefusalReason.NO_BANK_ACCOUNT), "REFUSED", "NO_BANK_ACCOUNT"),
                Arguments.of(refused(WithdrawalRefusalReason.INSUFFICIENT_FUNDS), "REFUSED", "INSUFFICIENT_FUNDS"),
                Arguments.of(returning("PAYOUT_FAILED"), "RETURN_PENDING", "PAYOUT_FAILED"),
                Arguments.of(returning("PLATFORM_FUNDS_SHORT"), "RETURN_PENDING", "PLATFORM_FUNDS_SHORT"),
                Arguments.of(returned("TERMS_REFUSED"), "RETURNED", "TERMS_REFUSED"),
                Arguments.of(returned("OPERATOR_RETURNED"), "RETURNED", "OPERATOR_RETURNED")
        );
    }

    @ParameterizedTest(name = "{1} shows {2}")
    @MethodSource("withdrawalsWithAReason")
    void aRefusedWithdrawalShowsItsRefusalReasonAndAReturningOneItsReturnReason(
            Withdrawal withdrawal,
            String status,
            String reason
    ) {
        // Guards the one reason field answering with the wrong column. A refused row has only a refusal reason and
        // a returning one only a return reason (two CHECKs make exactly one apply), so a client that shows the
        // reason would show a blank or the wrong cause. A return that follows an earlier failure shows the reason
        // of the event that raised the status last.
        WithdrawalStatusResponse response = WithdrawalStatusResponse.of(withdrawal, "PLN");

        assertThat(response).isEqualTo(new WithdrawalStatusResponse(REFERENCE, "75.00", "PLN", status, reason));
    }

    @Test
    void aPendingOrPaidWithdrawalShowsNoReason() {
        // Guards a reason shown where none applies, such as a stale return reason on a withdrawal that was
        // debited and then paid. A debited withdrawal has neither a refusal nor a return reason.
        Withdrawal paid = pending();
        paid.markPaid();

        assertThat(WithdrawalStatusResponse.of(pending(), "PLN").reason()).isNull();
        assertThat(WithdrawalStatusResponse.of(paid, "PLN"))
                .isEqualTo(new WithdrawalStatusResponse(REFERENCE, "75.00", "PLN", "PAID", null));
    }

    @Test
    void anAbsentReasonIsWrittenAsNullAndNeverLeftOut() {
        // Guards the field dropping out of the body when it does not apply: a client reads a status and a reason
        // at fixed names, and the documented example has all five fields in this order, as the bank account's
        // body does with its null last4.
        String pendingBody = mapper.writeValueAsString(WithdrawalStatusResponse.of(pending(), "PLN"));
        String returningBody = mapper.writeValueAsString(
                WithdrawalStatusResponse.of(returning("PAYOUT_FAILED"), "PLN")
        );

        assertThat(pendingBody).isEqualTo(
                "{\"reference\":\"" + REFERENCE + "\",\"amount\":\"75.00\",\"currency\":\"PLN\","
                        + "\"status\":\"PENDING\",\"reason\":null}"
        );
        assertThat(returningBody).isEqualTo(
                "{\"reference\":\"" + REFERENCE + "\",\"amount\":\"75.00\",\"currency\":\"PLN\","
                        + "\"status\":\"RETURN_PENDING\",\"reason\":\"PAYOUT_FAILED\"}"
        );
    }

    @ParameterizedTest(name = "{1} is shown as {2}")
    @CsvSource({
            "PLN, 75.0000, 75.00",
            "PLN, 0.5000, 0.50",
            "JPY, 1000.0000, 1000"
    })
    void theAmountIsShownAtTheCurrencysScaleFromTheStoredValue(String currency, String stored, String shown) {
        // Guards the stored NUMERIC(19,4) value reaching the client at the ledger's scale: a JPY amount would read
        // 1000.0000 and a PLN one 75.0000, and the status would not match the 202's receipt for the same
        // withdrawal (docs/adr/0030-amounts-in-responses-are-decimal-strings.md).
        Withdrawal withdrawal = Withdrawal.pending(wallet(currency), REFERENCE, new BigDecimal(stored));

        WithdrawalStatusResponse response = WithdrawalStatusResponse.of(withdrawal, currency);

        assertThat(response.amount()).isEqualTo(shown);
        assertThat(response.currency()).isEqualTo(currency);
    }
}
