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
            "0.5,     MGA, MGA amounts must be whole units",
            "10.50,   ISK, ISK amounts must be whole units"
    })
    void anAmountFinerThanTheCurrencyAllowsIsRefusedRatherThanRounded(String amount, String currency, String detail) {
        // Guards dust and movements of nothing. Rounding 10.001 would move a sum nobody asked for, and Postgres
        // would store 0.00001 as 0.0000. Fractional yen or ariary could never be paid out, because deposits
        // move whole units in those currencies. MGA is here because ISO gives it two decimals and the wallet
        // must not follow ISO. ISK is here because Stripe takes it in hundredths yet charges only whole krónur, so
        // a fractional ISK could never arrive by deposit.
        assertThatThrownBy(() -> AmountPrecision.canonical(new BigDecimal(amount), currency))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessage(detail);
    }

    @ParameterizedTest(name = "{1} {0} is accepted")
    @CsvSource({
            "10.5000, USD, 10.5",
            "100.00,  JPY, 100",
            "1E+2,    JPY, 100",
            "10.00,   ISK, 10"
    })
    void trailingZerosDoNotCountAsPrecision(String amount, String currency, String value) {
        // Guards refusing a correctly written amount: 10.5000 is 10.5, and 1E+2 is a whole number of yen. ISK
        // is here because Stripe writes it with two decimals, always 00, and 10.00 is a whole number of krónur.
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

    @ParameterizedTest(name = "{1} {0} on grid: {2}")
    @CsvSource({
            "10.50,        USD, true",
            "10.5000,      USD, true",
            "1E+2,         jpy, true",
            "10.123,       USD, false",
            "0.00001,      USD, false",
            "10.5,         JPY, false",
            "10.5,         isk, false",
            "1E+15,        USD, false",
            "1E+2147483647, USD, false"
    })
    void theNonThrowingCheckAgreesWithCanonical(String amount, String currency, boolean onGrid) {
        // The payment event consumer records a refusal instead of throwing, so it asks isOnGrid. If the two
        // checks drifted apart, an event could credit an amount a transfer of the same money would refuse.
        assertThat(AmountPrecision.isOnGrid(new BigDecimal(amount), currency)).isEqualTo(onGrid);
    }

    @ParameterizedTest(name = "{0} fits NUMERIC(19,4): {1}")
    @CsvSource({
            "10.123,               true",
            "0.0001,               true",
            "999999999999999.9999, true",
            "0.00001,              false",
            "10.12345,             false",
            "1E+15,                false",
            "1E-999999999,         false"
    })
    void fitsLedgerAcceptsExactlyWhatTheColumnHoldsWithoutRounding(String amount, boolean fits) {
        // A refused event's amount is stored only when the column keeps it as sent. Postgres would round
        // 0.00001 to 0.0000 and refuse 1E+15, which would send the refusal to the dead-letter topic.
        assertThat(AmountPrecision.fitsLedger(new BigDecimal(amount))).isEqualTo(fits);
    }

    @ParameterizedTest(name = "{1} {0} renders as {2}")
    @CsvSource({
            "25.0000,   USD, 25.00",
            "75,        USD, 75.00",
            "1E+2,      USD, 100.00",
            "0.0000,    USD, 0.00",
            "1000.0000, JPY, 1000",
            "0.0000,    JPY, 0",
            "5000.0000, MGA, 5000",
            "10.0000,   ISK, 10",
            "50.0000,   KWD, 50.00",
            "10.5000,   ISK, 10.5"
    })
    void anAmountRendersAtItsCurrencysScale(String amount, String currency, String rendered) {
        // Guards money printed at the ledger's scale of 4, or rounded to fit the grid. MGA is here because ISO gives
        // it two decimals and KWD because ISO gives it three, while the grid has none and two. The last case is an
        // ISK row written before ISK became whole units: it keeps its fraction rather than being rounded or failing
        // the page it is on.
        assertThat(AmountPrecision.render(new BigDecimal(amount), currency)).isEqualTo(rendered);
    }

    @ParameterizedTest(name = "{0} carries {1} decimals")
    @CsvSource({"USD, 2", "usd, 2", "JPY, 0", "ISK, 0", "MGA, 0", "KWD, 2"})
    void decimalsFollowTheGridRatherThanIso(String currency, int decimals) {
        // Guards decimals read from java.util.Currency, which a client would then use to format and check
        // amounts the wallet refuses or prints differently.
        assertThat(AmountPrecision.decimals(currency)).isEqualTo(decimals);
    }
}
