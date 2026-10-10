package com.flowwallet.wallet.withdrawal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class WithdrawalsUnavailableExceptionTest {
    @Test
    void aShortReserveIsA503ThatSaysNothingWasDebitedAndNothingAboutTheReserve() {
        // Guards a short platform reserve answered with another status, or a detail that says the money was
        // taken or names the reserve, which is the platform's and not the caller's to know. The remedy is a
        // retry with the same key, since nothing was written.
        // See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
        WithdrawalsUnavailableException exception = new WithdrawalsUnavailableException();

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exception.getMessage())
                .isEqualTo("Withdrawals are temporarily unavailable. Nothing was debited; "
                        + "retry later with the same Idempotency-Key.")
                .doesNotContainIgnoringCase("reserve")
                .doesNotContainPattern("[0-9]");
    }
}
