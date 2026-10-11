package com.flowwallet.payment.provider.dto;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderTransferTest {
    @ParameterizedTest(name = "{1} reversed of {0}: in full {2}")
    @CsvSource({
            "75.00, 0.00, false",
            "75.00, 74.99, false",
            "75.00, 75.00, true",
            "75.00, 75.0, true",
            "10.00, 10.00, true",
            "100.00, 75.00, false"
    })
    void aTransferIsReversedInFullOnceItsReversalsReachItsOwnAmount(String amount, String reversed, boolean inFull) {
        // Guards a transfer measured by anything but its own amount, a strict comparison that leaves a transfer
        // reversed to the cent holding money, and a scale apart (75.0 against 75.00) read as a part reversal.
        var transfer = new ProviderTransfer("tr_1", "acct_1", new BigDecimal(amount), new BigDecimal(reversed), "PLN");

        assertThat(transfer.reversedInFull()).isEqualTo(inFull);
    }
}
