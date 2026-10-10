package com.flowwallet.wallet.withdrawal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class WithdrawalNotFoundExceptionTest {
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "PLN, No such withdrawal in the PLN wallet",
            "JPY, No such withdrawal in the JPY wallet"
    })
    void aMissingWithdrawalIsA404ThatNamesOnlyTheWallet(String currency, String detail) {
        // Guards a detail that tells an unknown reference from another wallet's or another user's withdrawal, or
        // that quotes the reference the caller sent. All three get this one answer, so it describes nobody's
        // withdrawal.
        WithdrawalNotFoundException exception = new WithdrawalNotFoundException(currency);

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exception.getMessage()).isEqualTo(detail);
    }
}
