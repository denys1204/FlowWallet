package com.flowwallet.wallet.balance;

import com.flowwallet.platform.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletTest {
    private Wallet funded(String balance) {
        Wallet wallet = Wallet.open("alice", "USD");
        wallet.credit(new BigDecimal(balance));
        return wallet;
    }

    @Test
    void aDebitLargerThanTheBalanceIsRefusedAndLeavesTheBalanceUntouched() {
        // An overdraft is money the user does not have. The refusal must also come before the subtraction: a
        // refusal thrown afterwards would leave a negative balance in the managed entity, one flush away from
        // the database for any caller that catches the exception and carries on. The status must be 422, not
        // 409, which tells the client to use a new key, and the detail must carry no figures.
        Wallet wallet = funded("30.00");

        assertThatThrownBy(() -> wallet.debit(new BigDecimal("30.01")))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Insufficient funds in the USD wallet")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        assertThat(wallet.getBalance()).isEqualByComparingTo("30.00");
    }

    @Test
    void theWholeBalanceCanBeSentAndLeavesExactlyZero() {
        // Guards an off-by-one comparison (<= instead of <) that would refuse a legitimate full spend, and a
        // wrong value returned as the balance before, which the ledger row records. The balance and the amount
        // differ in scale, as a balance loaded from NUMERIC(19,4) and a request amount can, so the comparison
        // must be by value.
        Wallet wallet = funded("30.0000");

        BigDecimal balanceBefore = wallet.debit(new BigDecimal("30.00"));

        assertThat(balanceBefore).isEqualByComparingTo("30.00");
        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
    }

    @ParameterizedTest(name = "a debit of {0} is refused")
    @ValueSource(strings = {"0", "0.0000", "-5.00"})
    void aDebitThatIsNotPositiveIsRefused(String amount) {
        // A negative debit is a credit in disguise: subtracting it would raise the balance. A zero one would
        // record a movement of nothing. Callers validate before a transaction opens, so reaching here is a bug
        // and must fail rather than move money.
        Wallet wallet = funded("30.00");

        assertThatThrownBy(() -> wallet.debit(new BigDecimal(amount)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(wallet.getBalance()).isEqualByComparingTo("30.00");
    }
}
