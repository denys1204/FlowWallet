package com.flowwallet.wallet.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmountPrecisionTest {
    @ParameterizedTest(name = "{1} {0} is refused")
    @CsvSource({
            "10.001,  USD, USD amounts carry at most 2 decimal places",
            "0.00001, USD, USD amounts carry at most 2 decimal places",
            "1.5,     JPY, JPY amounts must be whole units",
            "0.5,     MGA, MGA amounts must be whole units"
    })
    void anAmountFinerThanTheCurrencyAllowsIsRefusedRatherThanRounded(String amount, String currency, String detail) {
        // Guards dust and movements of nothing. Rounding 10.001 would move a sum nobody asked for, and Postgres
        // would store 0.00001 as 0.0000. Fractional yen or ariary could never be paid out, because deposits
        // move whole units in those currencies. MGA is here because ISO gives it two decimals and the wallet
        // must not follow ISO.
        assertThatThrownBy(() -> AmountPrecision.canonical(new BigDecimal(amount), currency))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessage(detail);
    }

    @ParameterizedTest(name = "{1} {0} is accepted")
    @CsvSource({
            "10.5000, USD, 10.5",
            "100.00,  JPY, 100",
            "1E+2,    JPY, 100",
            "10.50,   ISK, 10.5"
    })
    void trailingZerosDoNotCountAsPrecision(String amount, String currency, String value) {
        // Guards refusing a correctly written amount: 10.5000 is 10.5, and 1E+2 is a whole number of yen. ISK
        // is here because ISO gives it no decimals while deposits give it two, so following ISO would strand
        // the 0.50 of a deposited 10.50.
        assertThat(AmountPrecision.canonical(new BigDecimal(amount), currency)).isEqualByComparingTo(value);
    }

    @ParameterizedTest(name = "{0} is refused")
    @ValueSource(strings = {"1000000000000000", "1E+15", "1E+999999999", "1E+2147483647"})
    void anAmountTheLedgerCannotHoldIsRefusedBeforeItIsRescaled(String amount) {
        // NUMERIC(19,4) leaves 15 integer digits, so no balance could cover a 16th, and without the bound the
        // caller would get "Insufficient funds" (422) for an amount that is wrong in itself. 1E+999999999 must
        // be refused before setScale tries to write out a billion digits. The last case has a scale at the int limit, where an int subtraction would overflow and let
        // the amount through.
        assertThatThrownBy(() -> AmountPrecision.canonical(new BigDecimal(amount), "USD"))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessage("Amount does not fit a wallet balance");
    }

    @Test
    void theLargestAmountTheLedgerHoldsIsAccepted() {
        // Guards an off-by-one in the size bound, which would refuse the top of the range NUMERIC(19,4) holds.
        assertThat(AmountPrecision.canonical(new BigDecimal("999999999999999.99"), "USD"))
                .isEqualByComparingTo("999999999999999.99");
    }

    @ParameterizedTest(name = "{1} {0} becomes {2}")
    @CsvSource({
            "25.00, USD, 25.0000",
            "25.5,  USD, 25.5000",
            "1E+2,  JPY, 100.0000"
    })
    void anAcceptedAmountComesBackAtTheLedgersScale(String amount, String currency, String written) {
        // A first answer is built from the amount in memory and a replay from the row, which NUMERIC(19,4)
        // hands back at scale 4. Unless this returns scale 4 too, the first answer prints 25.00 or 1E+2 and
        // every replay 25.0000 or 100.0000.
        assertThat(AmountPrecision.canonical(new BigDecimal(amount), currency).toString()).isEqualTo(written);
    }
}
