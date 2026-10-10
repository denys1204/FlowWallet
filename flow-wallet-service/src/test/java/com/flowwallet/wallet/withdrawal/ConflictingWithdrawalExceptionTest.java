package com.flowwallet.wallet.withdrawal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ConflictingWithdrawalExceptionTest {
    @Test
    void aKeyUsedForADifferentWithdrawalIsA409ThatNamesNoCause() {
        // Guards the conflict naming its cause or drifting to another status. The cause can be another amount,
        // another of the caller's wallets or another user's withdrawal, and naming it could describe someone
        // else's withdrawal; the remedy is a new key in every case.
        // See docs/adr/0005-client-supplied-idempotency-keys.md.
        ConflictingWithdrawalException exception = new ConflictingWithdrawalException();

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getMessage())
                .isEqualTo("This Idempotency-Key was already used for a different withdrawal. Retry with a new key.");
    }
}
