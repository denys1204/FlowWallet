package com.flowwallet.wallet.withdrawal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class WithdrawalRefusedExceptionTest {
    private static Stream<Arguments> details() {
        return Stream.of(
                Arguments.of(
                        WithdrawalRefusalReason.INSUFFICIENT_FUNDS,
                        "PLN",
                        "Insufficient funds in the PLN wallet. This Idempotency-Key is spent; "
                                + "withdraw a smaller amount or top up, then use a new key."
                ),
                Arguments.of(
                        WithdrawalRefusalReason.INSUFFICIENT_FUNDS,
                        "JPY",
                        "Insufficient funds in the JPY wallet. This Idempotency-Key is spent; "
                                + "withdraw a smaller amount or top up, then use a new key."
                ),
                Arguments.of(
                        WithdrawalRefusalReason.NO_BANK_ACCOUNT,
                        "PLN",
                        "No bank account is ready for payouts in PLN. This Idempotency-Key is spent; "
                                + "set a bank account, then use a new key."
                ),
                Arguments.of(
                        WithdrawalRefusalReason.NO_BANK_ACCOUNT,
                        "JPY",
                        "No bank account is ready for payouts in JPY. This Idempotency-Key is spent; "
                                + "set a bank account, then use a new key."
                )
        );
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(WithdrawalRefusalReason.class)
    void aRecordedRefusalIsA422(WithdrawalRefusalReason reason) {
        // Guards the refusal drifting to another status. 422 is what a client reads as a business refusal that
        // was recorded; a 400 reads as a malformed request that a corrected retry under the same key might fix,
        // and a 409 as a key used for a different withdrawal.
        WithdrawalRefusedException exception = new WithdrawalRefusedException(reason, "PLN");

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @ParameterizedTest(name = "{0} in {1}")
    @MethodSource("details")
    void eachReasonNamesTheCurrencyAndSaysWhatToDoNext(WithdrawalRefusalReason reason, String currency, String detail) {
        // Guards a detail that names the wrong wallet or gives the wrong remedy: the two reasons need different
        // ones, a top-up or a smaller amount for the funds and a bank account for the missing one. The platform
        // handler logs every 4xx detail, so the text is fixed wording around the currency and nothing else.
        WithdrawalRefusedException exception = new WithdrawalRefusedException(reason, currency);

        assertThat(exception.getMessage()).isEqualTo(detail);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(WithdrawalRefusalReason.class)
    void noDetailCarriesAFigure(WithdrawalRefusalReason reason) {
        // Guards a balance, an amount or a minimum reaching the detail and then the logs and the client. The
        // refusal is replayed under its key long after the request, so a figure in it would also be a stale one
        // (docs/adr/0016-error-model-and-status-codes.md).
        WithdrawalRefusedException exception = new WithdrawalRefusedException(reason, "PLN");

        assertThat(exception.getMessage()).doesNotContainPattern("[0-9]");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(WithdrawalRefusalReason.class)
    void eachDetailSaysTheKeyIsSpent(WithdrawalRefusalReason reason) {
        // Guards a client retrying a refused withdrawal under the same key after fixing the cause and waiting for
        // a different answer. The key keeps its 422, so the detail must say that and send the client to a new key,
        // or an automatic retry loops on the same refusal.
        // See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
        WithdrawalRefusedException exception = new WithdrawalRefusedException(reason, "PLN");

        assertThat(exception.getMessage())
                .contains("This Idempotency-Key is spent")
                .endsWith("then use a new key.");
    }
}
