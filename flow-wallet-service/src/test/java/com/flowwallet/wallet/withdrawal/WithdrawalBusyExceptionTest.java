package com.flowwallet.wallet.withdrawal;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class WithdrawalBusyExceptionTest {
    private static final String USER = "1f0c6d0e-5d4b-4c7e-9a2f-3b8e7d6c5a41";

    @Test
    void aLostLockIsA503ThatSaysNothingWasDebitedAndKeepsItsCauseOutOfTheDetail() {
        // Guards a rolled-back withdrawal answered as a 500, which hides the one fact the client needs (nothing
        // was debited, so the same key is safe to retry), and a cause message in the detail, which would carry the
        // database's own wording to the client. The cause itself stays on the exception for the platform
        // handler's log. See docs/adr/0011-wallet-row-locking.md.
        CannotAcquireLockException cause = new CannotAcquireLockException("deadlock detected for " + USER);

        WithdrawalBusyException exception = new WithdrawalBusyException(cause);

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exception.getMessage())
                .isEqualTo("The wallet is busy and nothing was debited. Retry with the same Idempotency-Key.")
                .doesNotContain(USER);
        assertThat(exception.getCause()).isSameAs(cause);
    }
}
